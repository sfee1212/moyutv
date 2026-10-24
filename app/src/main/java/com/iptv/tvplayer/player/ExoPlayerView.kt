package com.iptv.tvplayer.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.tvplayer.data.SettingsManager
import kotlinx.coroutines.delay

@Composable
fun ExoPlayerView(
    url: String,
    modifier: Modifier = Modifier
) {
    val timeoutSeconds = SettingsManager.timeoutSecondsState.value
    val autoReconnect = SettingsManager.autoReconnectState.value
    var stuckSeconds by remember { mutableStateOf(0) }
    var forceRecreate by remember { mutableStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(url, forceRecreate) {
        while(true) {
            delay(1000)
            if (!isPlaying) {
                stuckSeconds++
                if (stuckSeconds >= timeoutSeconds && autoReconnect) {
                    stuckSeconds = 0
                    forceRecreate++
                }
            } else {
                stuckSeconds = 0
            }
        }
    }

    key(url, forceRecreate) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val playerView = android.view.LayoutInflater.from(context).inflate(com.iptv.tvplayer.R.layout.custom_exo_player, null) as PlayerView

                playerView.apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    resizeMode = if (SettingsManager.aspectRatioState.value == "填充") {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    isFocusable = false
                    isFocusableInTouchMode = false
                }

                val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                    if (SettingsManager.userAgent.isNotBlank()) {
                        setUserAgent(SettingsManager.userAgent)
                    }
                    if (SettingsManager.headers.isNotBlank()) {
                        val headersMap = mutableMapOf<String, String>()
                        val parts = SettingsManager.headers.split(",")
                        for (part in parts) {
                            val kv = part.split(":", limit = 2)
                            if (kv.size == 2) {
                                headersMap[kv[0].trim()] = kv[1].trim()
                            }
                        }
                        setDefaultRequestProperties(headersMap)
                    }
                    setConnectTimeoutMs(10000)
                    setReadTimeoutMs(10000)
                }

                val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
                    setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    if (SettingsManager.decodeMode == "software") {
                        setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                            val allDecoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                            val swDecoders = allDecoders.filter { it.softwareOnly }
                            if (swDecoders.isNotEmpty()) swDecoders else allDecoders
                        }
                    }
                }

                val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                    )
                    .build()

                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true

                PlayerStateManager.reset() // 换台时清空旧数据

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            stuckSeconds = 0
                            isPlaying = true
                        }
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            isPlaying = false
                            if (autoReconnect && stuckSeconds > 2) {
                                forceRecreate++
                            }
                        }
                    }
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            PlayerStateManager.videoResolution.value = "${videoSize.width}x${videoSize.height}"
                        }
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        super.onTracksChanged(tracks)
                        // 遍历已选中的音视频轨道，读取编解码信息
                        for (group in tracks.groups) {
                            for (i in 0 until group.length) {
                                if (!group.isTrackSelected(i)) continue
                                val format = group.getTrackFormat(i)
                                val mime = format.sampleMimeType ?: continue
                                when {
                                    mime.startsWith("video/") -> {
                                        PlayerStateManager.videoCodec.value = mime.removePrefix("video/")
                                    }
                                    mime.startsWith("audio/") -> {
                                        PlayerStateManager.audioCodec.value = mime.removePrefix("audio/")
                                        if (format.channelCount > 0) {
                                            PlayerStateManager.audioChannelCount.value = format.channelCount
                                        }
                                    }
                                }
                            }
                        }
                    }
                })


                playerView
            },
            update = { playerView ->
                playerView.resizeMode = if (SettingsManager.aspectRatioState.value == "填充") {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { playerView ->
                playerView.player?.release()
                playerView.player = null
            }
        )
    }
}
