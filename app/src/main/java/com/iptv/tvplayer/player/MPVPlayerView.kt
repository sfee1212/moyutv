package com.iptv.tvplayer.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.iptv.tvplayer.data.SettingsManager

@Composable
fun MPVPlayerView(
    url: String,
    modifier: Modifier = Modifier
) {
    var isInitialized by remember { mutableStateOf(false) }
    
    // Safely load the URL only when MPV is fully initialized
    LaunchedEffect(url, isInitialized) {
        if (isInitialized && url.isNotEmpty()) {
            try {
                // Clear any old proxy settings
                MPV.INSTANCE.setOptionString("http-proxy", "")
                
                // Apply Aspect Ratio
                if (SettingsManager.aspectRatioState.value == "填充") {
                    MPV.INSTANCE.setOptionString("panscan", "1.0")
                } else {
                    MPV.INSTANCE.setOptionString("panscan", "0.0")
                    MPV.INSTANCE.setOptionString("video-aspect-override", "no")
                }
                
                // Apply Headers & UA
                if (SettingsManager.userAgent.isNotBlank()) {
                    MPV.INSTANCE.setOptionString("user-agent", SettingsManager.userAgent)
                }
                val customHeaders = SettingsManager.headers
                if (customHeaders.isNotBlank()) {
                    MPV.INSTANCE.setOptionString("http-header-fields", customHeaders)
                }

                val isHttps = url.startsWith("https", ignoreCase = true)
                if (isHttps) {
                    MPV.INSTANCE.setOptionString("demuxer-lavf-o", "tls_verify=0")
                    MPV.INSTANCE.setOptionString("stream-lavf-o", "tls_verify=0")
                } else {
                    MPV.INSTANCE.setOptionString("demuxer-lavf-o", "")
                    MPV.INSTANCE.setOptionString("stream-lavf-o", "")
                }

                MPV.INSTANCE.command(arrayOf("loadfile", url))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Timeout Monitor & Reconnect
    val timeoutSeconds = SettingsManager.timeoutSecondsState.value
    val autoReconnect = SettingsManager.autoReconnectState.value
    var lastPlaybackTime by remember { mutableStateOf(-1) }
    var stuckSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(url, isInitialized) {
        if (isInitialized && url.isNotEmpty()) {
            PlayerStateManager.reset() // 换台时清空旧数据
            while (true) {
                delay(1000)
                try {
                    val ptime = MPV.INSTANCE.getPropertyInt("playback-time") ?: -1
                    val isPaused = MPV.INSTANCE.getPropertyBoolean("core-idle") ?: false
                    if (ptime == lastPlaybackTime) {
                        if (!isPaused) stuckSeconds++
                    } else {
                        stuckSeconds = 0
                        lastPlaybackTime = ptime
                    }

                    // 分辨率
                    val w = MPV.INSTANCE.getPropertyInt("width") ?: 0
                    val h = MPV.INSTANCE.getPropertyInt("height") ?: 0
                    if (w > 0 && h > 0) {
                        PlayerStateManager.videoResolution.value = "${w}x${h}"
                    }

                    // 视频编解码
                    val vCodec = MPV.INSTANCE.getPropertyString("video-codec") ?: ""
                    if (vCodec.isNotBlank()) PlayerStateManager.videoCodec.value = vCodec

                    // 音频编解码
                    val aCodec = MPV.INSTANCE.getPropertyString("audio-codec") ?: ""
                    if (aCodec.isNotBlank()) PlayerStateManager.audioCodec.value = aCodec

                    // 音频声道数
                    val aCh = MPV.INSTANCE.getPropertyInt("audio-params/channel-count") ?: 0
                    if (aCh > 0) PlayerStateManager.audioChannelCount.value = aCh

                    if (stuckSeconds >= timeoutSeconds) {
                        stuckSeconds = 0
                        if (autoReconnect) {
                            MPV.INSTANCE.command(arrayOf("loadfile", url))
                        }
                    }
                } catch (e: Exception) {
                    stuckSeconds++
                    if (stuckSeconds >= timeoutSeconds && autoReconnect) {
                        stuckSeconds = 0
                        try { MPV.INSTANCE.command(arrayOf("loadfile", url)) } catch (e2: Exception) {}
                    }
                }
            }
        }
    }


    AndroidView(
        modifier = modifier,
        factory = { context ->
            val surfaceView = android.view.SurfaceView(context).apply {
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        MPV.INSTANCE.create(context)
                        MPV.INSTANCE.setOptionString("sub-font-provider", "none")
                        MPV.INSTANCE.setOptionString("hwdec", "auto")
                        MPV.INSTANCE.setOptionString("vo", "gpu")
                        MPV.INSTANCE.setOptionString("tls-verify", "no")
                        MPV.INSTANCE.init()
                        MPV.INSTANCE.attachSurface(holder.surface)
                        
                        MPV.INSTANCE.observeProperty("eof-reached", MPV.MpvFormat.MPV_FORMAT_FLAG)
                        MPV.INSTANCE.addObserver(object : MPV.EventObserver {
                            override fun eventProperty(property: String) {}
                            override fun eventProperty(property: String, value: Long) {}
                            override fun eventProperty(property: String, value: Boolean) {
                                if (property == "eof-reached" && value && SettingsManager.autoReconnect) {
                                    MPV.INSTANCE.command(arrayOf("loadfile", url))
                                }
                            }
                            override fun eventProperty(property: String, value: String) {}
                            override fun eventProperty(property: String, value: Double) {}
                            override fun event(eventId: Int) {}
                        })
                        isInitialized = true
                    }

                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                        MPV.INSTANCE.setPropertyString("android-surface-size", "${width}x$height")
                    }

                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        isInitialized = false
                        MPV.INSTANCE.setPropertyString("vo", "null")
                        MPV.INSTANCE.detachSurface()
                        MPV.INSTANCE.destroy()
                    }
                })
            }
            
            surfaceView
        },
        update = { view ->
        }
    )
}
