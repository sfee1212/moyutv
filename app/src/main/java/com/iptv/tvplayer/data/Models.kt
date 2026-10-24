package com.iptv.tvplayer.data

data class Channel(
    val name: String,
    val urls: List<String>,
    val logo: String? = null,
    val drmType: String? = null,
    val drmKey: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int = 0
)

data class Playlist(
    val categories: List<String>,
    val channelsMap: Map<String, List<Channel>>
)
