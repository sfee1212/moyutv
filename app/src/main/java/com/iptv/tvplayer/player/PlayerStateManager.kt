package com.iptv.tvplayer.player

import androidx.compose.runtime.mutableStateOf

object PlayerStateManager {

    /** 视频分辨率，如"1920x1080" */
    val videoResolution = mutableStateOf("")

    /** 视频编码原始字符串，如"hevc"/"avc"/"av1" */
    val videoCodec = mutableStateOf("")

    /** 音频编码原始字符串，如"aac"/"ac3"/"eac3" */
    val audioCodec = mutableStateOf("")

    /** 音频声道数（整数），0 = 未知 */
    val audioChannelCount = mutableStateOf(0)

    // ── 辅助计算属性──────────────────────────────────────────────────────────

    /** 根据分辨率高度推断画质标签：4K / HD / SD */
    val resolutionLabel: String
        get() {
            val h = videoResolution.value.substringAfter("x").toIntOrNull() ?: 0
            return when {
                h >= 2160 -> "4K"
                h >= 720  -> "HD"
                h > 0     -> "SD"
                else      -> "SD"
            }
        }

    /** 宽高比标签：16:9 / 4:3 / 21:9 ?*/
    val aspectRatioLabel: String
        get() {
            val parts = videoResolution.value.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return "16:9"
            val ratio = w.toFloat() / h.toFloat()
            return when {
                ratio >= 2.3f -> "21:9"
                ratio >= 1.7f -> "16:9"
                ratio >= 1.3f -> "4:3"
                else          -> "16:9"
            }
        }

    /** 音频显示标签，如 "AAC Stereo"?"Dolby 5.1" */
    val audioLabel: String
        get() {
            val raw = audioCodec.value.lowercase()
            val displayCodec = when {
                raw.contains("eac3") || raw.contains("ec-3") -> "Dolby D+"
                raw.contains("ac3") || raw.contains("ac-3")  -> "Dolby"
                raw.contains("truehd")                        -> "TrueHD"
                raw.contains("dts")                           -> "DTS"
                raw.contains("opus")                          -> "Opus"
                raw.contains("mp3") || raw.contains("mp2")   -> "MP3"
                raw.contains("flac")                          -> "FLAC"
                else                                          -> "AAC"
            }
            val chLabel = when (audioChannelCount.value) {
                1    -> "Mono"
                2    -> "Stereo"
                6    -> "5.1"
                8    -> "7.1"
                else -> ""
            }
            return if (chLabel.isNotBlank()) "$displayCodec $chLabel" else displayCodec
        }

    /** 视频编码显示标签，如 "H.265"?"AV1" */
    val videoCodecLabel: String
        get() {
            val raw = videoCodec.value.lowercase()
            return when {
                raw.contains("hevc") || raw.contains("h265") || raw.contains("h.265") -> "H.265"
                raw.contains("avc")  || raw.contains("h264") || raw.contains("h.264") -> "H.264"
                raw.contains("av1")                                                    -> "AV1"
                raw.contains("vp9")                                                    -> "VP9"
                raw.contains("vp8")                                                    -> "VP8"
                raw.contains("mpeg4") || raw.contains("mp4v")                          -> "MPEG4"
                raw.contains("mpeg2") || raw.contains("mpg2")                          -> "MPEG2"
                raw.isNotBlank()                                                        -> videoCodec.value.uppercase()
                else                                                                    -> "MPEG4"
            }
        }

    /** 换台时重置所有字段*/
    fun reset() {
        videoResolution.value = ""
        videoCodec.value = ""
        audioCodec.value = ""
        audioChannelCount.value = 0
    }
}
