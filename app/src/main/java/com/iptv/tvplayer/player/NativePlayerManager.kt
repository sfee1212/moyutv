package com.iptv.tvplayer.player

import android.content.Context
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.data.SettingsManager
import com.iptv.tvplayer.proxy.ProxyManager
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.*

object NativePlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var httpDataSourceFactory: androidx.media3.datasource.okhttp.OkHttpDataSource.Factory? = null
    private var previousPlayerHadDrm = false
    private var previousProxyStr = ""
    private var currentView: View? = null
    private var mpvJob: Job? = null
    private var timeoutJob: Job? = null
    var onAutoSwitchRequested: (() -> Unit)? = null

    // ===== 弱网优化状态 =====
    // 已成功起播过(弱网容忍加码): 卡缓冲超时额外 +10s 再切台, 避免短暂弱网被切走
    @Volatile private var playedOnce = false
    // 自上次成功起播以来的连续播放错误数 (仅 EXO: 首错静默重连, 第2错才切台)
    private var errorsSinceReady = 0
    // 播放代次: 换台/换内核后作废挂起的延迟重连
    private var playGen = 0
    // 起播成功后的卡缓冲起始时刻 (诊断日志用)
    private var rebufferStartMs = 0L

    private fun logRebufferStart() {
        if (playedOnce && rebufferStartMs == 0L) {
            rebufferStartMs = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d("NativePlayer", "Rebuffer: start")
        }
    }

    private fun logRebufferEnd() {
        if (rebufferStartMs != 0L) {
            val ms = android.os.SystemClock.elapsedRealtime() - rebufferStartMs
            rebufferStartMs = 0L
            android.util.Log.d("NativePlayer", "Rebuffer: end after ${ms}ms")
        }
    }

    private fun startTimeoutJob() {
        timeoutJob?.cancel()
        val graceExtra = if (playedOnce) 10_000L else 0L
        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(SettingsManager.timeoutSeconds * 1000L + graceExtra)
            onAutoSwitchRequested?.invoke()
        }
    }

    fun cancelTimeoutJob() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private var stuckCheckJob: Job? = null

    private fun startStuckCheckJob(reloadAction: (() -> Unit)? = null) {
        stuckCheckJob?.cancel()
        stuckCheckJob = CoroutineScope(Dispatchers.Main).launch {
            var lastPosition = -1L
            var lastMpvTime = -1.0
            var stuckSeconds = 0
            
            while (isActive) {
                delay(1000)
                var isCurrentlyPlaying = false
                var progressChanged = false

                if (exoPlayer != null) {
                    val isPlaying = exoPlayer?.isPlaying ?: false
                    val currentPos = exoPlayer?.currentPosition ?: -1L
                    val isLive = exoPlayer?.isCurrentMediaItemLive == true || exoPlayer?.duration == androidx.media3.common.C.TIME_UNSET
                    if (isPlaying && currentPos >= 0) {
                        isCurrentlyPlaying = true
                        if (currentPos != lastPosition || isLive) {
                            progressChanged = true
                            lastPosition = currentPos
                        }
                    }
                } else if (mpvInitialized) {
                    val isPaused = try { MPV.INSTANCE.getPropertyBoolean("core-idle") ?: false } catch (e: Exception) { false }
                    if (!isPaused) {
                        isCurrentlyPlaying = true
                        progressChanged = true // As long as it's not idle, we assume it's playing fine
                    }
                }

                if (isCurrentlyPlaying) {
                    if (progressChanged) {
                        stuckSeconds = 0
                    } else {
                        stuckSeconds++
                        if (stuckSeconds >= 15) { // Increased to 15 seconds to prevent premature restarts during buffering
                            stuckSeconds = 0
                            if (SettingsManager.autoReconnect && reloadAction != null) {
                                reloadAction()
                            } else {
                                onAutoSwitchRequested?.invoke()
                            }
                        }
                    }
                } else {
                    stuckSeconds = 0
                }
            }
        }
    }

    private fun cancelStuckCheckJob() {
        stuckCheckJob?.cancel()
        stuckCheckJob = null
    }
    
    var onPlayerReady: (() -> Unit)? = null
    var onPlayerBuffering: (() -> Unit)? = null
    
    fun getCurrentView(): View? = currentView

    fun createExoPlayer(context: Context, url: String, drmType: String? = null, drmKey: String? = null): View {
        var actualUrl = url
        var localProxyStr = ""
        playGen++
        playedOnce = false
        errorsSinceReady = 0
        rebufferStartMs = 0L
        
        val proxyRegex = Regex("^(sk|socks5|proxy):/?/?([^/&\\uFF06]+)[/&\\uFF06](https?://.*)$", RegexOption.IGNORE_CASE)
        val match = proxyRegex.find(actualUrl)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase().replace("proxy", "http")
            val proxyHostPort = match.groupValues[2]
            val targetUrl = match.groupValues[3]
            
            if (scheme == "sk" || scheme == "socks5") {
                val base64Proxy = android.util.Base64.encodeToString(proxyHostPort.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                actualUrl = "http://127.0.0.1:8080/_p$base64Proxy/$targetUrl"
                // Do not set localProxyStr because we are routing through our local StreamProxyHandler
                localProxyStr = ""
            } else {
                localProxyStr = "$scheme://$proxyHostPort"
                actualUrl = targetUrl
            }
        }

        val hasDrm = !drmType.isNullOrBlank() && !drmKey.isNullOrBlank()
        val isReusing = exoPlayer != null && currentView is PlayerView && !hasDrm && !previousPlayerHadDrm && previousProxyStr == localProxyStr

        if (!isReusing) {
            release()
            previousPlayerHadDrm = hasDrm
            previousProxyStr = localProxyStr
            val playerView = LayoutInflater.from(context).inflate(R.layout.custom_exo_player, null) as PlayerView
            playerView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            playerView.isFocusable = false
            playerView.isFocusableInTouchMode = false
            playerView.setKeepContentOnPlayerReset(true)
            val contentFrame = findAspectRatioLayout(playerView)
            if (SettingsManager.aspectRatio == "16:9") {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                contentFrame?.setAspectRatio(16f / 9f)
            } else {
                contentFrame?.setAspectRatio(0f)
                playerView.resizeMode = when (SettingsManager.aspectRatio) {
                    "填充" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "裁剪" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }

            val proxyStr = localProxyStr
            val okHttpClientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .dns(object : okhttp3.Dns {
                      override fun lookup(hostname: String): List<java.net.InetAddress> {
                          val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                          val ipv4 = addresses.filterIsInstance<java.net.Inet4Address>()
                          return if (ipv4.isNotEmpty()) ipv4 else addresses
                      }
                })
                .addInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(request)
                    val urlStr = request.url.toString()
                    if (urlStr.contains(".mpd", ignoreCase = true) || urlStr.contains(".xml", ignoreCase = true) || urlStr.contains(".php", ignoreCase = true)) {
                        val contentType = response.header("Content-Type")?.lowercase() ?: ""
                        if (!contentType.contains("video/") && !contentType.contains("audio/")) {
                            val bodyBytes = response.body?.bytes() ?: return@addInterceptor response
                            val bodyString = String(bodyBytes)
                            if (bodyString.contains("<?xml", ignoreCase = true) || bodyString.contains("<MPD", ignoreCase = true) || bodyString.contains("dash:schema", ignoreCase = true)) {
                                val fixedString = bodyString.replace(Regex("&(?!amp;|lt;|gt;|apos;|quot;|#\\d+;|#[xX][0-9a-fA-F]+;)"), "&amp;")
                                val newBody = okhttp3.ResponseBody.create(response.body?.contentType(), fixedString)
                                return@addInterceptor response.newBuilder().body(newBody).build()
                            } else {
                                val newBody = okhttp3.ResponseBody.create(response.body?.contentType(), bodyBytes)
                                return@addInterceptor response.newBuilder().body(newBody).build()
                            }
                        }
                    }
                    response
                }

            if (proxyStr.isNotEmpty()) {
                val isHttp = proxyStr.startsWith("http")
                val isSk = proxyStr.startsWith("sk")
                val cleanProxyStr = proxyStr.substringAfter("://")
                val proxyParts = cleanProxyStr.split(":")
                val host = proxyParts[0]
                val port = if (proxyParts.size > 1) proxyParts[1].toIntOrNull() ?: 1080 else 1080
                okHttpClientBuilder.proxySelector(object : java.net.ProxySelector() {
                    override fun select(uri: java.net.URI?): List<java.net.Proxy> {
                        var typeStr = if (isHttp) "http" else "socks"
                        if (isSk) {
                            typeStr = com.iptv.tvplayer.utils.ProxyUtils.detectProxyType(host, port, "socks")
                        }
                        val type = if (typeStr == "http") java.net.Proxy.Type.HTTP else java.net.Proxy.Type.SOCKS
                        return listOf(java.net.Proxy(type, java.net.InetSocketAddress(host, port)))
                    }
                    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
                })
            }

            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClientBuilder.build()).apply {
                NativePlayerManager.httpDataSourceFactory = this
                if (SettingsManager.userAgent.isNotBlank()) setUserAgent(SettingsManager.userAgent)
                if (SettingsManager.headers.isNotBlank()) {
                    val headersMap = mutableMapOf<String, String>()
                    SettingsManager.headers.split(",").forEach { part ->
                        val kv = part.split(":", limit = 2)
                        if (kv.size == 2) headersMap[kv[0].trim()] = kv[1].trim()
                    }
                    setDefaultRequestProperties(headersMap)
                }
            }
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)

            val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink? {
                    return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                }
            }.apply {
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
                if (SettingsManager.decodeMode == "software") {
                    setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                        val allDecoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                        val swDecoders = allDecoders.filter { it.softwareOnly }
                        if (swDecoders.isNotEmpty()) swDecoders else allDecoders
                    }
                }
            }

            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    25000, // minBufferMs
                    60000, // maxBufferMs
                    4000,  // bufferForPlaybackMs - start playing after 4s buffer to prevent freezing
                    8000   // bufferForPlaybackAfterRebufferMs - 弱网: 恢复时多攒2s缓冲, 减少反复卡顿
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(30000, true)
                .build()

            val drmSessionManager = if (hasDrm && drmType == "clearkey") {
                val jwkJson = buildClearKeyJwkJson(drmKey!!)
                if (jwkJson != null) {
                    val drmCallback = androidx.media3.exoplayer.drm.LocalMediaDrmCallback(jwkJson.toByteArray())
                    androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(drmCallback)
                } else null
            } else null

            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                )

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(dataSourceFactory)
                .setLiveTargetOffsetMs(10000)
                // 弱网: 分片/播放列表加载失败重试次数 5 -> 10, 瞬时抖动不轻易判为致命错误
                .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(10))
            if (drmSessionManager != null) {
                mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            }

            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxAudioChannelCount(2) // Force stereo track if available, to fix low volume on 5.1 downmix
                )
            }

            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setAudioAttributes(audioAttributes, true) // Handle audio focus and volume properly
                .setHandleAudioBecomingNoisy(true)
                .build()

            exoPlayer?.addListener(object : Player.Listener {
                private var hasRetriedForHls = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) {
                        startTimeoutJob()
                        logRebufferStart()
                        onPlayerBuffering?.invoke()
                    } else if (playbackState == Player.STATE_READY) {
                        playedOnce = true
                        errorsSinceReady = 0
                        cancelTimeoutJob()
                        logRebufferEnd()
                        onPlayerReady?.invoke()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("NativePlayer", "EXO onPlayerError: $error", error)
                    var currentCause: Throwable? = error
                    var isUnrecognized = false
                    while (currentCause != null) {
                        if (currentCause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) {
                            isUnrecognized = true
                            break
                        }
                        currentCause = currentCause.cause
                    }

                    if (isUnrecognized && !hasRetriedForHls) {
                        hasRetriedForHls = true
                        val currentItem = exoPlayer?.currentMediaItem
                        if (currentItem != null && currentItem.localConfiguration?.mimeType != androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
                            val newItem = currentItem.buildUpon().setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build()
                            exoPlayer?.setMediaItem(newItem)
                            exoPlayer?.prepare()
                            exoPlayer?.playWhenReady = true
                            return
                        }
                    }

                    errorsSinceReady++
                    if (SettingsManager.autoReconnect || errorsSinceReady <= 1) {
                        // 弱网优化: 错误后退避 800ms 再静默重连(避免 error→prepare 紧循环烧 CPU/加剧卡顿);
                        // autoReconnect 关闭时也先给一次静默重连机会, 连续第 2 个错误才切台
                        val gen = playGen
                        val target = exoPlayer
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(800)
                            if (gen == playGen && exoPlayer === target && target != null
                                && target.playbackState == Player.STATE_IDLE) {
                                target.prepare()
                                target.playWhenReady = true
                            }
                        }
                    } else {
                        onAutoSwitchRequested?.invoke()
                    }
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        PlayerStateManager.videoResolution.value = "${videoSize.width}x${videoSize.height}"
                    }
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    exoPlayer?.videoFormat?.let { format ->
                        if (format.width > 0 && format.height > 0) {
                            PlayerStateManager.videoResolution.value = "${format.width}x${format.height}"
                        }
                    }
                    exoPlayer?.audioFormat?.let { format ->
                        val mime = format.sampleMimeType ?: ""
                        val codecName = mime.removePrefix("audio/")
                        PlayerStateManager.audioCodec.value = codecName
                        
                        if (codecName.contains("ac3") || codecName.contains("eac3") || codecName.contains("dts")) {
                            android.widget.Toast.makeText(context, "注意: 此频道为 $codecName 杜比/DTS音轨，部分国产手机(如小米)若播放无声，请在设置中切换为 MPV 或 IjkPlayer 内核", android.widget.Toast.LENGTH_LONG).show()
                        }
                        
                        if (format.channelCount > 0) {
                            PlayerStateManager.audioChannelCount.value = format.channelCount
                        }
                    }
                }
            })

            playerView.player = exoPlayer
            currentView = playerView
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            val finalUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.iptv.tvplayer.script.ScriptManager.resolveUrl(actualUrl)
            }

            if (finalUrl.isEmpty()) {
                onAutoSwitchRequested?.invoke()
                return@launch
            }

            var cleanUrl = finalUrl
            val customHeaders = mutableMapOf<String, String>()
            val splitChar = if (finalUrl.contains("#")) "#" else if (finalUrl.contains("|")) "|" else null
            if (splitChar != null) {
                val parts = finalUrl.split(splitChar, limit = 2)
                cleanUrl = parts[0]
                val headerQuery = parts[1]
                headerQuery.split("&").forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) {
                        customHeaders[kv[0].trim()] = kv[1].trim()
                    }
                }
            }

            // 动态将自定义 headers 注入到 ExoPlayer 数据源工厂中
            httpDataSourceFactory?.let { factory ->
                val baseHeaders = mutableMapOf<String, String>()
                if (SettingsManager.headers.isNotBlank()) {
                    SettingsManager.headers.split(",").forEach { part ->
                        val kv = part.split(":", limit = 2)
                        if (kv.size == 2) baseHeaders[kv[0].trim()] = kv[1].trim()
                    }
                }
                customHeaders.forEach { (k, v) ->
                    baseHeaders[k] = v
                }
                factory.setDefaultRequestProperties(baseHeaders)
            }

            // TVBox 内嵌代理注释 (&socks5= / &socks5:// / &proxy= / &http_proxy=):
            //   http(s) 目标经本地 StreamProxyHandler /_p转发(SOCKS5h 或 HTTP CONNECT);
            //   rtsp+代理 场景已在 MainActivity 入口自动切至 MPV 内核, 此处仅去注释兜底。
            if (localProxyStr.isEmpty() && !cleanUrl.contains("127.0.0.1:8080/")) {
                val ann = com.iptv.tvplayer.proxy.ProxyManager.parseProxyAnnotation(cleanUrl)
                if (ann.hasProxy) {
                    val el = ann.cleanUrl.lowercase(java.util.Locale.US)
                    if (el.startsWith("http://") || el.startsWith("https://")) {
                        val proxySpec = (if (ann.httpProxy) "http://" else "") + "${ann.proxyHost}:${ann.proxyPort}"
                        val b64 = android.util.Base64.encodeToString(proxySpec.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                        cleanUrl = "http://127.0.0.1:8080/_p$b64/${ann.cleanUrl}"
                    } else {
                        cleanUrl = ann.cleanUrl   // rtp/rtsp 等: 去注释(rtp 走 /rtpts; rtsp 已切 MPV)
                    }
                }
            }

            var finalMimeType: String? = null

            // rtp:// / udp:// 组播直播: EXO 无 UDP 数据源, 经 LocalServer /rtpts 转成 HTTP-TS;
            // 直接指定 video/mp2t 免嗅探(否则首包异常会触发"按HLS重试"死循环)
            com.iptv.tvplayer.proxy.RtpTsInputStream.toLocalUrl(cleanUrl)?.let {
                cleanUrl = it
                finalMimeType = androidx.media3.common.MimeTypes.VIDEO_MP2T
            }

            if (cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains(".xml", ignoreCase = true) || (hasDrm && !cleanUrl.contains(".m3u8", ignoreCase = true))) {
                finalMimeType = androidx.media3.common.MimeTypes.APPLICATION_MPD
            } else if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                finalMimeType = androidx.media3.common.MimeTypes.APPLICATION_M3U8
            } else if (cleanUrl.contains(".flv", ignoreCase = true)) {
                finalMimeType = androidx.media3.common.MimeTypes.VIDEO_FLV
            }

            android.util.Log.i("NativePlayer", "EXO play: url=$cleanUrl mime=$finalMimeType")

            val mediaItemBuilder = MediaItem.Builder().setUri(cleanUrl)
            if (finalMimeType != null) {
                mediaItemBuilder.setMimeType(finalMimeType)
            }

            if (hasDrm) {
                if (drmType?.lowercase() == "clearkey") {
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.CLEARKEY_UUID).build()
                    )
                } else if (drmType?.lowercase() == "widevine") {
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                            .setLicenseUri(drmKey!!)
                            .build()
                    )
                }
            }
            
            exoPlayer?.setMediaItem(mediaItemBuilder.build())
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
        
        startTimeoutJob()
        startStuckCheckJob {
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
        onPlayerBuffering?.invoke()
        return currentView!!
    }

    private fun buildClearKeyJwkJson(drmKey: String): String? {
        return try {
            val parts = drmKey.split(":")
            if (parts.size != 2) return null
            val kidHex = parts[0].trim()
            val keyHex = parts[1].trim()
            
            val kidBytes = hexStringToByteArray(kidHex)
            val keyBytes = hexStringToByteArray(keyHex)
            
            val kidBase64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP).trim()
            val keyBase64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP).trim()
            
            """{"keys":[{"kty":"oct","kid":"$kidBase64","k":"$keyBase64"}],"type":"temporary"}"""
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private var mpvInitialized = false
    private val mpvLock = Any()
    private var pendingMpvUrl: String? = null
    private var currentRtspRelay: com.iptv.tvplayer.proxy.Socks5TcpRelay? = null
    private var pendingMpvUserAgent: String? = null
    private var pendingMpvHeaders: String? = null
    private var mpvWidth: Long = 0
    private var mpvHeight: Long = 0

    private fun updateMpvResolution() {
        if (mpvWidth > 0 && mpvHeight > 0) {
            PlayerStateManager.videoResolution.value = "${mpvWidth}x${mpvHeight}"
        }
    }

    /**
     * socks5://ip:port/rtsp://... (或 sk:// / proxy://) 前缀格式:
     * 建立本地 RTSP 隧道(SOCKS5 或 HTTP CONNECT), 返回可直连的 rtsp://127.0.0.1:port/path。
     * 失败返回 null(调用方回退直连播放)。
     */
    private fun startRtspTunnel(proxyHostPort: String, targetUrl: String, httpProxy: Boolean): String? {
        return try {
            val u = java.net.URI(targetUrl)
            val th = u.host
            val tp = if (u.port > 0) u.port else 554
            if (th.isNullOrEmpty()) null else {
                val ci = proxyHostPort.lastIndexOf(':')
                if (ci <= 0) return null
                val ph = proxyHostPort.substring(0, ci)
                val pp = proxyHostPort.substring(ci + 1).toIntOrNull() ?: return null
                currentRtspRelay?.stop()
                val relay = com.iptv.tvplayer.proxy.Socks5TcpRelay(ph, pp, httpProxy, th, tp)
                relay.start()
                currentRtspRelay = relay
                val path = (if (u.rawPath.isNullOrEmpty()) "/" else u.rawPath) +
                        (if (!u.rawQuery.isNullOrEmpty()) "?${u.rawQuery}" else "")
                android.util.Log.i("NativePlayer", "RTSP tunnel: proxy=$proxyHostPort target=$targetUrl -> rtsp://127.0.0.1:${relay.localPort}$path")
                "rtsp://127.0.0.1:${relay.localPort}$path"
            }
        } catch (e: Exception) {
            android.util.Log.w("NativePlayer", "RTSP 代理隧道建立失败, 直连播放: $e")
            null
        }
    }

    fun createMPVPlayer(context: Context, url: String): View {
        var actualUrl = url
        var localProxyStr = ""
        playGen++
        playedOnce = false
        rebufferStartMs = 0L
        // rtsp 前缀代理的隧道延后创建: release() 会 stop currentRtspRelay, 必须在 release 之后建
        var pendingRtspTunnel: Triple<String, String, Boolean>? = null   // proxyHostPort, targetUrl, httpProxy
        fun materializeRtspTunnel() {
            val p = pendingRtspTunnel ?: return
            pendingRtspTunnel = null
            actualUrl = startRtspTunnel(p.first, p.second, p.third) ?: p.second
        }
        
        // 目标支持 rtsp://(socks5://ip:port/rtsp://...), FFmpeg RTSP 客户端不支持代理, 走本地隧道
        // 注意: 完整目标URL必须在捕获组内 — ((?:https?|rtsp)://.*) 而非 (https?|rtsp)://.*
        val proxyRegex = Regex("^(sk|socks5|proxy):/?/?([^/&\\uFF06]+)[/&\\uFF06]((?:https?|rtsp)://.*)$", RegexOption.IGNORE_CASE)
        val match = proxyRegex.find(actualUrl)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase().replace("proxy", "http")
            val proxyHostPort = match.groupValues[2]
            val targetUrl = match.groupValues[3]
            
            if (scheme == "sk" || scheme == "socks5") {
                if (targetUrl.startsWith("rtsp://", ignoreCase = true)) {
                    // Route through local Socks5TcpRelay (SOCKS5 隧道), 同内嵌注释路径
                    pendingRtspTunnel = Triple(proxyHostPort, targetUrl, false)
                    localProxyStr = ""
                } else {
                    // Route through local StreamProxyHandler (127.0.0.1:8080) which uses Socks5hSocketFactory.
                    // MPV's http-proxy option does NOT work reliably with stream_callback URLs on Android 5.
                    // The local OkHttp proxy already handles SOCKS5h correctly (DNS resolved by proxy server).
                    val base64Proxy = android.util.Base64.encodeToString(
                        proxyHostPort.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                    )
                    actualUrl = "http://127.0.0.1:8080/_p$base64Proxy/$targetUrl"
                    localProxyStr = "" // no further proxy needed; handled by StreamProxyHandler
                }
            } else {
                if (targetUrl.startsWith("rtsp://", ignoreCase = true)) {
                    // Route through local Socks5TcpRelay (HTTP CONNECT 隧道)
                    pendingRtspTunnel = Triple(proxyHostPort, targetUrl, true)
                    localProxyStr = ""
                } else {
                    localProxyStr = "$scheme://$proxyHostPort"
                    actualUrl = targetUrl
                }
            }
        }
        
        fun loadMpvUrl(targetUrl: String) {
            CoroutineScope(Dispatchers.Main).launch {
                var finalUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.iptv.tvplayer.script.ScriptManager.resolveUrl(targetUrl)
                }
                
                if (finalUrl.isEmpty()) {
                    onAutoSwitchRequested?.invoke()
                    return@launch
                }

                // TVBox 内嵌代理注释: rtsp://host/path&socks5=ip:port 等
                val ann = com.iptv.tvplayer.proxy.ProxyManager.parseProxyAnnotation(finalUrl)
                if (ann.hasProxy && localProxyStr.isEmpty()) {
                    val el = ann.cleanUrl.lowercase()
                    when {
                        el.startsWith("rtsp://") -> {
                            // FFmpeg RTSP 客户端不支持代理 → 本地 SOCKS5/HTTP-CONNECT 隧道
                            finalUrl = try {
                                val u = java.net.URI(ann.cleanUrl)
                                val th = u.host
                                val tp = if (u.port > 0) u.port else 554
                                if (th.isNullOrEmpty()) ann.cleanUrl else {
                                    currentRtspRelay?.stop()
                                    val relay = com.iptv.tvplayer.proxy.Socks5TcpRelay(
                                        ann.proxyHost, ann.proxyPort, ann.httpProxy, th, tp)
                                    relay.start()
                                    currentRtspRelay = relay
                                    val path = (if (u.rawPath.isNullOrEmpty()) "/" else u.rawPath) +
                                            (if (!u.rawQuery.isNullOrEmpty()) "?${u.rawQuery}" else "")
                                    "rtsp://127.0.0.1:${relay.localPort}$path"
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("NativePlayer", "RTSP 代理隧道建立失败, 直连播放: $e")
                                ann.cleanUrl
                            }
                        }
                        el.startsWith("http://") || el.startsWith("https://") -> {
                            // 复用本地 StreamProxyHandler (socks5 走 Socks5hSocketFactory; http_proxy= 走 HTTP CONNECT)
                            val proxySpec = (if (ann.httpProxy) "http://" else "") + "${ann.proxyHost}:${ann.proxyPort}"
                            val b64 = android.util.Base64.encodeToString(
                                proxySpec.toByteArray(),
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                            "http://127.0.0.1:8080/_p$b64/${ann.cleanUrl}"
                        }
                        else -> ann.cleanUrl   // rtp/udp 等: 去注释直接播(MPV 原生支持)
                    }
                } else if (ann.cleanUrl != finalUrl) {
                    finalUrl = ann.cleanUrl
                }

                // Route non-proxied HTTP and HTTPS streams through the local OkHttp proxy to bypass MPV's buggy network stack
                val lowerUrl = finalUrl.lowercase()
                if (localProxyStr.isEmpty() && (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) && !finalUrl.contains("127.0.0.1:8080/")) {
                    val splitIdx = finalUrl.indexOfAny(charArrayOf('#', '|'))
                    if (splitIdx != -1) {
                        val urlPart = finalUrl.substring(0, splitIdx)
                        val headerPart = finalUrl.substring(splitIdx)
                        finalUrl = "http://127.0.0.1:8080/_p/$urlPart$headerPart"
                    } else {
                        finalUrl = "http://127.0.0.1:8080/_p/$finalUrl"
                    }
                }
                
                // localProxyStr is already socks5://host:port or http://host:port — no further detection needed

                var cleanUrl = finalUrl
                val customHeaders = mutableMapOf<String, String>()
                val splitChar = if (finalUrl.contains("#")) "#" else if (finalUrl.contains("|")) "|" else null
                if (splitChar != null) {
                    val parts = finalUrl.split(splitChar, limit = 2)
                    cleanUrl = parts[0]
                    val headerQuery = parts[1]
                    headerQuery.split("&").forEach { param ->
                        val kv = param.split("=", limit = 2)
                        if (kv.size == 2) {
                            customHeaders[kv[0].trim()] = kv[1].trim()
                        }
                    }
                }

                var ua = SettingsManager.userAgent
                val customHeadersLower = mutableMapOf<String, String>()
                customHeaders.forEach { (k, v) ->
                    if (k.equals("user-agent", ignoreCase = true)) {
                        ua = v
                    } else {
                        customHeadersLower[k.lowercase()] = v
                    }
                }

                val headersList = mutableListOf<String>()
                if (SettingsManager.headers.isNotBlank()) {
                    SettingsManager.headers.split(",").forEach { part ->
                        val kv = part.split(":", limit = 2)
                        if (kv.size == 2) {
                            val key = kv[0].trim()
                            val value = kv[1].trim()
                            if (!customHeadersLower.containsKey(key.lowercase())) {
                                headersList.add("$key: $value")
                            }
                        }
                    }
                }
                customHeaders.forEach { (k, v) ->
                    if (!k.equals("user-agent", ignoreCase = true)) {
                        headersList.add("$k: $v")
                    }
                }
                val headersString = headersList.joinToString("\r\n")

                synchronized(mpvLock) {
                    if (mpvInitialized) {
                        val isHttps = url.startsWith("https", ignoreCase = true)
                        val isRtsp = cleanUrl.startsWith("rtsp", ignoreCase = true)
                        if (isHttps) {
                            MPV.INSTANCE.setOptionString("demuxer-lavf-o", "tls_verify=0")
                        } else if (isRtsp) {
                            // RTSP 优先 RTP-over-TCP(interleaved): 经本地代理隧道必需, 弱网也更稳
                            MPV.INSTANCE.setOptionString("demuxer-lavf-o", "rtsp_flags=prefer_tcp")
                        } else {
                            MPV.INSTANCE.setOptionString("demuxer-lavf-o", "")
                        }
                        // MPV/FFmpeg reads the proxy from http-proxy option.
                        // socks5://host:port and http://host:port are both supported by FFmpeg's http-proxy.
                        if (localProxyStr.isNotEmpty()) {
                            MPV.INSTANCE.setOptionString("http-proxy", localProxyStr)
                        } else {
                            MPV.INSTANCE.setOptionString("http-proxy", "")
                        }
                        val lavfOpts = buildString {
                            if (localProxyStr.isNotEmpty()) append("http_proxy=$localProxyStr")
                            if (isHttps) { if (isNotEmpty()) append(","); append("tls_verify=0") }
                        }
                        MPV.INSTANCE.setOptionString("stream-lavf-o", lavfOpts)
                        if (ua.isNotBlank()) {
                            MPV.INSTANCE.setOptionString("user-agent", ua)
                        } else {
                            MPV.INSTANCE.setOptionString("user-agent", "")
                        }
                        MPV.INSTANCE.setOptionString("http-header-fields", headersString)
                        
                        android.util.Log.i("NativePlayer", "MPV loadfile: url=$cleanUrl proxy=$localProxyStr")
                MPV.INSTANCE.command(arrayOf("loadfile", cleanUrl))
                        pendingMpvUrl = null
                        pendingMpvUserAgent = null
                        pendingMpvHeaders = null
                    } else {
                        pendingMpvUrl = cleanUrl
                        pendingMpvUserAgent = ua
                        pendingMpvHeaders = headersString
                    }
                }
            }
        }

        if (currentView is SurfaceView) {
            materializeRtspTunnel()   // 复用路径不 release, 隧道可直接创建
            startTimeoutJob()
            startStuckCheckJob {
                loadMpvUrl(actualUrl)
            }
            onPlayerBuffering?.invoke()
            loadMpvUrl(actualUrl)
            return currentView!!
        }
        
        release()
        materializeRtspTunnel()   // release 已停掉旧隧道, 新隧道必须在 release 之后创建
        val surfaceView = SurfaceView(context)
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(mpvLock) {
                    // 先尝试以绝对路径重新加载（对 Android 14/HyperOS 的兜底）
                    if (!`is`.xyz.mpv.MPV.isLoaded) {
                        `is`.xyz.mpv.MPV.loadLibraries(context)
                    }
                    if (!`is`.xyz.mpv.MPV.isLoaded) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val errorMsg = `is`.xyz.mpv.MPV.loadError ?: "Unknown error"
                            val shortMsg = when {
                                errorMsg.contains("dlopen") -> "Library load failed (dlopen)"
                                errorMsg.contains("symbol") -> "Symbol missing, library version incompatible"
                                errorMsg.contains("not found") -> "Library not found"
                                else -> errorMsg.take(60)
                            }
                            android.widget.Toast.makeText(context, "MPV load failed: $shortMsg\nSwitched to EXO", android.widget.Toast.LENGTH_LONG).show()
                            SettingsManager.decoderType = "EXO"
                            onAutoSwitchRequested?.invoke()
                        }
                        return
                    }
                    if (mpvInitialized) return
                    MPV.INSTANCE.create(context)
                    mpvInitialized = true
                    MPV.INSTANCE.setOptionString("sub-font-provider", "none")
                    
                    // MPV Hardware decoding fallback & compatibility options
                    if (SettingsManager.decodeMode == "software") {
                        MPV.INSTANCE.setOptionString("hwdec", "no")
                        MPV.INSTANCE.setOptionString("vo", "gpu")
                    } else {
                        // Check Android version to apply correct video output context
                        if (android.os.Build.VERSION.SDK_INT <= 29) {
                            // For Android 10 and below (older TVs): force direct hardware decoding
                            // This bypasses OpenGL ES and renders directly to the Android Surface
                            MPV.INSTANCE.setOptionString("hwdec", "mediacodec")
                            MPV.INSTANCE.setOptionString("vo", "mediacodec_embed")
                        } else {
                            // For Android 11+ (modern TVs): use robust GPU rendering
                            MPV.INSTANCE.setOptionString("hwdec", "auto")
                            MPV.INSTANCE.setOptionString("vo", "gpu")
                            MPV.INSTANCE.setOptionString("gpu-context", "android")
                        }
                    }
                    MPV.INSTANCE.setOptionString("deinterlace", "auto") // Auto deinterlace for 1080i streams
                    MPV.INSTANCE.setOptionString("framedrop", "vo") // Drop video frames if audio gets out of sync
                    val aspect = SettingsManager.aspectRatio
                    if (aspect == "填充") {
                        MPV.INSTANCE.setOptionString("keepaspect", "no")
                        MPV.INSTANCE.setOptionString("panscan", "0.0")
                        MPV.INSTANCE.setOptionString("video-aspect-override", "no")
                    } else if (aspect == "裁剪") {
                        MPV.INSTANCE.setOptionString("keepaspect", "yes")
                        MPV.INSTANCE.setOptionString("panscan", "1.0")
                        MPV.INSTANCE.setOptionString("video-aspect-override", "no")
                    } else if (aspect == "16:9") {
                        MPV.INSTANCE.setOptionString("keepaspect", "yes")
                        MPV.INSTANCE.setOptionString("panscan", "0.0")
                        MPV.INSTANCE.setOptionString("video-aspect-override", "16:9")
                    } else { // 原始
                        MPV.INSTANCE.setOptionString("keepaspect", "yes")
                        MPV.INSTANCE.setOptionString("panscan", "0.0")
                        MPV.INSTANCE.setOptionString("video-aspect-override", "no")
                    }
                    
                    // MPV.INSTANCE.setOptionString("profile", "fast") // Removed to improve video quality
                    MPV.INSTANCE.setOptionString("tls-verify", "no")
                    MPV.INSTANCE.setOptionString("ytdl", "no")
                    // stream-lavf-o and demuxer-lavf-o are now handled in loadMpvUrl based on protocol
                    if (localProxyStr.isNotEmpty() && !localProxyStr.startsWith("sk")) {
                        if (localProxyStr.startsWith("socks", ignoreCase = true)) {
                            MPV.INSTANCE.setOptionString("stream-lavf-o", "http_proxy=$localProxyStr")
                        } else {
                            MPV.INSTANCE.setOptionString("http-proxy", localProxyStr)
                            MPV.INSTANCE.setOptionString("stream-lavf-o", "")
                        }
                    } else if (localProxyStr.isEmpty()) {
                        MPV.INSTANCE.setOptionString("http-proxy", "")
                        MPV.INSTANCE.setOptionString("stream-lavf-o", "")
                    }
                    MPV.INSTANCE.setOptionString("cache", "yes")
                    MPV.INSTANCE.setOptionString("demuxer-max-bytes", "128M") // Increased for poor networks
                    MPV.INSTANCE.setOptionString("demuxer-max-back-bytes", "32M")
                    MPV.INSTANCE.setOptionString("demuxer-readahead-secs", "20") // Increased for poor networks
                    MPV.INSTANCE.setOptionString("vd-lavc-threads", "4") // Multi-thread decoding to speed up start
                    MPV.INSTANCE.setOptionString("cache-secs", "20")
                    MPV.INSTANCE.setOptionString("cache-pause-wait", "5") // 弱网: 卡顿后缓存攒到5s再恢复, 减少连续卡顿
                    MPV.INSTANCE.setOptionString("network-timeout", SettingsManager.timeoutSeconds.toString())
                    
                    val uaToSet = pendingMpvUserAgent ?: SettingsManager.userAgent
                    if (uaToSet.isNotBlank()) {
                        MPV.INSTANCE.setOptionString("user-agent", uaToSet)
                    } else {
                        MPV.INSTANCE.setOptionString("user-agent", "")
                    }
                    
                    val headersToSet = pendingMpvHeaders ?: if (SettingsManager.headers.isNotBlank()) {
                        SettingsManager.headers.replace(",", "\r\n")
                    } else ""
                    if (headersToSet.isNotEmpty()) {
                        MPV.INSTANCE.setOptionString("http-header-fields", headersToSet)
                    } else {
                        MPV.INSTANCE.setOptionString("http-header-fields", "")
                    }
                    
                    MPV.INSTANCE.init()
                    MPV.INSTANCE.attachSurface(holder.surface)
                    MPV.INSTANCE.observeProperty("eof-reached", MPV.MpvFormat.MPV_FORMAT_FLAG)
                    MPV.INSTANCE.observeProperty("paused-for-cache", MPV.MpvFormat.MPV_FORMAT_FLAG)
                    MPV.INSTANCE.observeProperty("playback-time", MPV.MpvFormat.MPV_FORMAT_DOUBLE)
                    MPV.INSTANCE.observeProperty("width", MPV.MpvFormat.MPV_FORMAT_INT64)
                    MPV.INSTANCE.observeProperty("height", MPV.MpvFormat.MPV_FORMAT_INT64)
                    MPV.INSTANCE.observeProperty("audio-codec", MPV.MpvFormat.MPV_FORMAT_STRING)
                    
                    MPV.INSTANCE.addObserver(object : MPV.EventObserver {
                        override fun eventProperty(property: String) {}
                        override fun eventProperty(property: String, value: Long) {
                            if (!mpvInitialized) return
                            if (property == "width") {
                                mpvWidth = value
                                updateMpvResolution()
                            } else if (property == "height") {
                                mpvHeight = value
                                updateMpvResolution()
                            }
                        }
                        override fun eventProperty(property: String, value: Boolean) {
                            if (!mpvInitialized) return
                            if (property == "eof-reached" && value) {
                                if (SettingsManager.autoReconnect) {
                                    loadMpvUrl(actualUrl)
                                } else {
                                    onAutoSwitchRequested?.invoke()
                                }
                            } else if (property == "paused-for-cache") {
                                if (value) {
                                    startTimeoutJob()
                                    logRebufferStart()
                                    onPlayerBuffering?.invoke()
                                } else {
                                    playedOnce = true
                                    cancelTimeoutJob()
                                    logRebufferEnd()
                                    onPlayerReady?.invoke()
                                }
                            }
                        }
                        override fun eventProperty(property: String, value: String) {
                            if (!mpvInitialized) return
                            if (property == "audio-codec") {
                                val cleanCodec = value.lowercase().trim()
                                val codecName = when {
                                    cleanCodec.contains("ac3") || cleanCodec.contains("eac3") || cleanCodec.contains("dolby") -> "Dolby"
                                    cleanCodec.contains("aac") -> "AAC"
                                    cleanCodec.contains("mp3") -> "MP3"
                                    cleanCodec.contains("flac") -> "FLAC"
                                    else -> value.uppercase()
                                }
                                PlayerStateManager.audioCodec.value = value
                            }
                        }
                        override fun eventProperty(property: String, value: Double) {
                            if (!mpvInitialized) return
                            if (property == "playback-time" && value > 0) {
                                playedOnce = true
                                cancelTimeoutJob()
                                logRebufferEnd()
                                onPlayerReady?.invoke()
                            }
                        }
                        override fun event(eventId: Int) {
                            if (!mpvInitialized) return
                        }
                    })
                    startTimeoutJob()
                    startStuckCheckJob {
                        loadMpvUrl(actualUrl)
                    }
                    onPlayerBuffering?.invoke()
                    
                    val urlToLoad = pendingMpvUrl
                    if (urlToLoad != null) {
                        MPV.INSTANCE.command(arrayOf("loadfile", urlToLoad))
                        pendingMpvUrl = null
                        pendingMpvUserAgent = null
                        pendingMpvHeaders = null
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(mpvLock) {
                    if (mpvInitialized) {
                        try {
                            MPV.INSTANCE.setPropertyString("android-surface-size", "${width}x$height")
                        } catch (e: Exception) {}
                    }
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(mpvLock) {
                    if (mpvInitialized) {
                        try {
                            MPV.INSTANCE.setPropertyString("vo", "null")
                            MPV.INSTANCE.detachSurface()
                            MPV.INSTANCE.destroy()
                        } catch (e: Exception) {}
                        mpvInitialized = false
                    }
                }
            }
        })
        currentView = surfaceView
        loadMpvUrl(actualUrl)
        return surfaceView
    }

    fun release() {
        cancelTimeoutJob()
        cancelStuckCheckJob()
        currentRtspRelay?.stop()
        currentRtspRelay = null
        exoPlayer?.release()
        exoPlayer = null
        previousPlayerHadDrm = false
        synchronized(mpvLock) {
            if (mpvInitialized) {
                try {
                    MPV.INSTANCE.setPropertyString("vo", "null")
                    MPV.INSTANCE.detachSurface()
                    MPV.INSTANCE.destroy()
                } catch (e: Exception) {}
                mpvInitialized = false
            }
        }
        pendingMpvUrl = null
        pendingMpvUserAgent = null
        pendingMpvHeaders = null
        currentView = null
        mpvWidth = 0
        mpvHeight = 0
        PlayerStateManager.reset()
    }

    fun updateAspectRatio() {
        val aspect = SettingsManager.aspectRatio
        val view = currentView
        
        if (exoPlayer != null && view is PlayerView) {
            val contentFrame = findAspectRatioLayout(view)
            if (aspect == "16:9") {
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                contentFrame?.setAspectRatio(16f / 9f)
            } else {
                contentFrame?.setAspectRatio(0f)
                view.resizeMode = when (aspect) {
                    "填充" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "裁剪" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        } else if (mpvInitialized) {
            try {
                if (aspect == "填充") {
                    MPV.INSTANCE.setPropertyString("keepaspect", "no")
                    MPV.INSTANCE.setPropertyDouble("panscan", 0.0)
                    MPV.INSTANCE.setPropertyString("video-aspect-override", "no")
                } else if (aspect == "裁剪") {
                    MPV.INSTANCE.setPropertyString("keepaspect", "yes")
                    MPV.INSTANCE.setPropertyDouble("panscan", 1.0)
                    MPV.INSTANCE.setPropertyString("video-aspect-override", "no")
                } else if (aspect == "16:9") {
                    MPV.INSTANCE.setPropertyString("keepaspect", "yes")
                    MPV.INSTANCE.setPropertyDouble("panscan", 0.0)
                    MPV.INSTANCE.setPropertyString("video-aspect-override", "16:9")
                } else { // 原始
                    MPV.INSTANCE.setPropertyString("keepaspect", "yes")
                    MPV.INSTANCE.setPropertyDouble("panscan", 0.0)
                    MPV.INSTANCE.setPropertyString("video-aspect-override", "no")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun findAspectRatioLayout(view: View): AspectRatioFrameLayout? {
        if (view is AspectRatioFrameLayout) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findAspectRatioLayout(view.getChildAt(i))
                if (child != null) return child
            }
        }
        return null
    }
}




