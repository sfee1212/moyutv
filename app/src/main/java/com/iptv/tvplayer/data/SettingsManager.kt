package com.iptv.tvplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class Subscription(val name: String, val url: String)

data class SocksGroup(val name: String, var ips: List<String>) {
    fun getNextIp(): String? {
        if (ips.isEmpty()) return null
        val currentIndex = SettingsManager.socksGroupIndexMap[name] ?: 0
        val ip = ips[currentIndex % ips.size]
        SettingsManager.socksGroupIndexMap[name] = (currentIndex + 1) % ips.size
        return ip
    }
}

object SettingsManager {
    private const val PREF_NAME = "iptv_settings"
    private const val KEY_DECODER = "decoder_type"
    private const val KEY_SUBSCRIPTION_LIST = "subscription_list"
    private const val KEY_EPG_LIST = "epg_list"
    private const val KEY_ASPECT_RATIO = "aspect_ratio"
    private const val KEY_TIMEOUT = "timeout_seconds"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_USER_AGENT = "user_agent"
    private const val KEY_HEADERS = "headers"
    private const val KEY_LAST_PLAYED_URL = "last_played_url"
    private const val KEY_ACTIVE_SUBSCRIPTION = "active_subscription"
    private const val KEY_ACTIVE_SUBSCRIPTIONS = "active_subscriptions"
    private const val KEY_ACTIVE_EPG = "active_epg"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_SHOW_CHANNEL_NUMBER = "show_channel_number"
    private const val KEY_SHOW_NETWORK_SPEED = "show_network_speed"
    private const val KEY_DECODE_MODE = "decode_mode"
    private const val KEY_SHOW_CHANNEL_LOGO = "show_channel_logo"

    private lateinit var prefs: SharedPreferences

    val socksGroupIndexMap = mutableMapOf<String, Int>()
    
    var localPlaylistPath = ""

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        localPlaylistPath = java.io.File(context.filesDir, "custom_playlist.txt").absolutePath

        // Copy test.txt from assets to custom_playlist.txt (仅首次; 不能覆盖用户修改)
        val file = java.io.File(localPlaylistPath)
        if (!file.exists() || file.length() == 0L) {
            try {
                context.assets.open("test.txt").use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Migrate old default single url to the new list format
        val oldSub = prefs.getString("subscription_url", "") ?: ""
        if (oldSub.isNotEmpty() && subscriptions.isEmpty()) {
            subscriptions = listOf(Subscription("默认接口", oldSub))
            prefs.edit().remove("subscription_url").apply()
        }

        // Migrate old default subscriptions pointing to 129.159.177.216 or local path
        try {
            val currentSubs = subscriptions
            val migratedSubs = currentSubs.filter { sub ->
                sub.name != "内置节目" && sub.url != "http://129.159.177.216/api/test" && sub.url != localPlaylistPath
            }
            if (migratedSubs != currentSubs) {
                subscriptions = migratedSubs
            }

            val activeSet = activeSubscriptionUrls
            val filteredActive = activeSet.filter { 
                it != "http://129.159.177.216/api/test" && it != localPlaylistPath 
            }.toSet()
            if (activeSet != filteredActive) {
                activeSubscriptionUrls = filteredActive
            }
            val curActive = prefs.getString(KEY_ACTIVE_SUBSCRIPTION, "") ?: ""
            if (curActive == "http://129.159.177.216/api/test" || curActive == localPlaylistPath) {
                prefs.edit().remove(KEY_ACTIVE_SUBSCRIPTION).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Migrate old default EPG URL (only clear truly outdated/broken sources)
        val currentEpgJson = prefs.getString(KEY_EPG_LIST, "") ?: ""
        if (currentEpgJson.contains("epg.112114.xyz") || currentEpgJson.contains("taksssss") || currentEpgJson.contains("fanmingming") || currentEpgJson.contains("epg.112114.free.hr") || currentEpgJson.contains("epg.112114.eu.org")) {
            prefs.edit().remove(KEY_EPG_LIST).apply()
            prefs.edit().remove(KEY_ACTIVE_EPG).apply()
        }
        val activeEpg = prefs.getString(KEY_ACTIVE_EPG, "") ?: ""
        if (activeEpg.contains("taksssss") || activeEpg.contains("epg.112114.xyz") || activeEpg.contains("fanmingming") || activeEpg.contains("epg.112114.free.hr") || activeEpg.contains("epg.112114.eu.org")) {
            prefs.edit().remove(KEY_ACTIVE_EPG).apply()
        }
        
        // Initialize reactive states
        decoderTypeState.value = decoderType
        subscriptionUrlState.value = subscriptionUrl
        activeEpgUrlState.value = activeEpgUrl
        aspectRatioState.value = aspectRatio
        timeoutSecondsState.value = timeoutSeconds
        autoReconnectState.value = autoReconnect
        showChannelNumberState.value = showChannelNumber
        showNetworkSpeedState.value = showNetworkSpeed
        showTimeWeatherState.value = showTimeWeather
        decodeModeState.value = decodeMode
        enableTbsX5State.value = enableTbsX5
        showChannelLogoState.value = showChannelLogo
    }

    val showChannelNumberState = mutableStateOf(true)
    var showChannelNumber: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CHANNEL_NUMBER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_CHANNEL_NUMBER, value).apply()
            showChannelNumberState.value = value
        }

    val showNetworkSpeedState = mutableStateOf(true)
    var showNetworkSpeed: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NETWORK_SPEED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_NETWORK_SPEED, value).apply()
            showNetworkSpeedState.value = value
        }

    val showTimeWeatherState = mutableStateOf(true)
    var showTimeWeather: Boolean
        get() = prefs.getBoolean("show_time_weather", true)
        set(value) {
            prefs.edit().putBoolean("show_time_weather", value).apply()
            showTimeWeatherState.value = value
        }

    /** 是否显示频道台标 */
    val showChannelLogoState = mutableStateOf(true)
    var showChannelLogo: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CHANNEL_LOGO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_CHANNEL_LOGO, value).apply()
            showChannelLogoState.value = value
        }

    val decoderTypeState = mutableStateOf("EXO")
    var decoderType: String
        get() = prefs.getString(KEY_DECODER, "EXO") ?: "EXO"
        set(value) {
            prefs.edit().putString(KEY_DECODER, value).apply()
            decoderTypeState.value = value
        }

    val decodeModeState = mutableStateOf("hardware")
    var decodeMode: String
        get() = prefs.getString(KEY_DECODE_MODE, "hardware") ?: "hardware"
        set(value) {
            prefs.edit().putString(KEY_DECODE_MODE, value).apply()
            decodeModeState.value = value
        }

    var subscriptions: List<Subscription>
        get() {
            val defaultJsonStr = "[]"
            var jsonStr = prefs.getString(KEY_SUBSCRIPTION_LIST, defaultJsonStr) ?: defaultJsonStr
            if (jsonStr == "[{\"name\":\"内置节目\",\"url\":\"$localPlaylistPath\"}]") jsonStr = defaultJsonStr
            val list = mutableListOf<Subscription>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    val url = obj.getString("url")
                    // Filter out old unused defaults
                    if (name == "网络列表(备用)" || name == "本地列表(扫码上传)" || name == "内置节目" || url == "http://129.159.177.216/api/test" || url == localPlaylistPath) {
                        continue
                    }
                    list.add(Subscription(name, url))
                }
            } catch (e: Exception) { }
            return list
        }
        set(value) {
            val array = JSONArray()
            value.forEach { 
                val obj = JSONObject()
                obj.put("name", it.name)
                obj.put("url", it.url)
                array.put(obj)
            }
            prefs.edit().putString(KEY_SUBSCRIPTION_LIST, array.toString()).apply()
        }
        
    // The active subscription URL (deprecated, used as fallback for backwards compatibility)
    var activeSubscriptionUrl: String
        get() = prefs.getString(KEY_ACTIVE_SUBSCRIPTION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_SUBSCRIPTION, value).apply()
            val oldSet = prefs.getStringSet(KEY_ACTIVE_SUBSCRIPTIONS, emptySet()) ?: emptySet()
            if (!oldSet.contains(value)) {
                activeSubscriptionUrls = setOf(value)
            }
        }

    // The new multi-select active subscription URLs
    var activeSubscriptionUrls: Set<String>
        get() {
            val jsonSet = getSetFromJson(KEY_ACTIVE_SUBSCRIPTIONS + "_json")
            if (!jsonSet.isNullOrEmpty()) return jsonSet
            
            // Migration
            try {
                val oldSet = prefs.getStringSet(KEY_ACTIVE_SUBSCRIPTIONS, null)
                if (!oldSet.isNullOrEmpty()) {
                    val copy = oldSet.toSet()
                    putSetToJson(KEY_ACTIVE_SUBSCRIPTIONS + "_json", copy)
                    prefs.edit().remove(KEY_ACTIVE_SUBSCRIPTIONS).apply()
                    return copy
                }
            } catch (e: Exception) {}
            
            val fallback = activeSubscriptionUrl
            if (fallback.isNotEmpty()) return setOf(fallback)
            val firstSub = subscriptions.firstOrNull()?.url
            return if (firstSub != null) setOf(firstSub) else emptySet()
        }
        set(value) {
            putSetToJson(KEY_ACTIVE_SUBSCRIPTIONS + "_json", value)
            if (value.isNotEmpty() && !value.contains(activeSubscriptionUrl)) {
                prefs.edit().putString(KEY_ACTIVE_SUBSCRIPTION, value.first()).apply()
            }
            activeSubscriptionUrlsState.value = value
        }

    val activeSubscriptionUrlsState = mutableStateOf(emptySet<String>())
    val subscriptionUrlState = mutableStateOf("") // kept for UI compatibility if needed
    val subscriptionUrl: String
        get() {
            val active = activeSubscriptionUrls.firstOrNull() ?: activeSubscriptionUrl
            if (active.isNotEmpty()) return active
            return subscriptions.firstOrNull()?.url ?: ""
        }

    var epgs: List<Subscription>
        get() {
            val defaultJsonStr = "[{\"name\":\"默认EPG\",\"url\":\"https://epg.v1.mk/fy.xml\"}]"
            var jsonStr = prefs.getString(KEY_EPG_LIST, defaultJsonStr) ?: defaultJsonStr
            if (jsonStr == "[]" || jsonStr.contains("taksssss") || jsonStr.contains("fanmingming") || jsonStr.contains("epg.112114.free.hr") || jsonStr.contains("epg.112114.eu.org")) jsonStr = defaultJsonStr
            val list = mutableListOf<Subscription>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(Subscription(obj.getString("name"), obj.getString("url")))
                }
            } catch (e: Exception) { }
            return list
        }
        set(value) {
            val array = JSONArray()
            value.forEach { 
                val obj = JSONObject()
                obj.put("name", it.name)
                obj.put("url", it.url)
                array.put(obj)
            }
            prefs.edit().putString(KEY_EPG_LIST, array.toString()).apply()
        }

    var activeEpgUrl: String
        get() = prefs.getString(KEY_ACTIVE_EPG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_EPG, value).apply()
            activeEpgUrlState.value = epgUrl
        }

    val activeEpgUrlState = mutableStateOf("")
    val epgUrl: String
        get() {
            val active = activeEpgUrl
            if (active.isNotEmpty()) return active
            return epgs.firstOrNull()?.url ?: ""
        }

    val aspectRatioState = mutableStateOf("原始")
    var aspectRatio: String
        get() = prefs.getString(KEY_ASPECT_RATIO, "原始") ?: "原始"
        set(value) {
            prefs.edit().putString(KEY_ASPECT_RATIO, value).apply()
            aspectRatioState.value = value
        }

    val timeoutSecondsState = mutableStateOf(15)
    var timeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, 15)
        set(value) {
            prefs.edit().putInt(KEY_TIMEOUT, value).apply()
            timeoutSecondsState.value = value
        }
        
    val autoReconnectState = mutableStateOf(false)
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
            autoReconnectState.value = value
        }

    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        set(value) = prefs.edit().putString(KEY_USER_AGENT, value).apply()

    var headers: String
        get() = prefs.getString(KEY_HEADERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HEADERS, value).apply()

    val enableTbsX5State = mutableStateOf(false)
    var enableTbsX5: Boolean
        get() = prefs.getBoolean("enable_tbs_x5", false)
        set(value) {
            prefs.edit().putBoolean("enable_tbs_x5", value).apply()
            enableTbsX5State.value = value
        }


    var youtubeProxyUrl: String
        get() = prefs.getString("youtube_proxy_url", "") ?: ""
        set(value) = prefs.edit().putString("youtube_proxy_url", value).apply()

    var proxiedCategories: Set<String>
        get() = getSetFromJson("proxied_categories_json") ?: emptySet()
        set(value) = putSetToJson("proxied_categories_json", value)

    var categoryProxyMap: Map<String, String>
        get() {
            val jsonStr = prefs.getString("category_proxy_map", null)
            if (jsonStr != null) {
                try {
                    val jsonObj = JSONObject(jsonStr)
                    val map = mutableMapOf<String, String>()
                    jsonObj.keys().forEach { key ->
                        map[key] = jsonObj.getString(key)
                    }
                    return map
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Migration from proxiedCategories
            val oldProxied = proxiedCategories
            if (oldProxied.isNotEmpty()) {
                val map = oldProxied.associateWith { "代理池" }
                putMapToJson("category_proxy_map", map)
                proxiedCategories = emptySet()
                return map
            }
            return emptyMap()
        }
        set(value) = putMapToJson("category_proxy_map", value)
        
    var customSocksGroups: List<SocksGroup>
        get() {
            val jsonStr = prefs.getString("custom_socks_groups", null) ?: return emptyList()
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<SocksGroup>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "")
                    if (name.isEmpty()) continue
                    
                    val ipsArray = obj.optJSONArray("ips")
                    val ips = mutableListOf<String>()
                    if (ipsArray != null) {
                        for (j in 0 until ipsArray.length()) {
                            val ip = ipsArray.optString(j)
                            if (ip.isNotEmpty()) ips.add(ip)
                        }
                    }
                    list.add(SocksGroup(name, ips))
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
                return emptyList()
            }
        }
        set(value) {
            try {
                val array = JSONArray()
                for (group in value) {
                    val obj = JSONObject()
                    obj.put("name", group.name)
                    val ipsArray = JSONArray()
                    for (ip in group.ips) {
                        ipsArray.put(ip)
                    }
                    obj.put("ips", ipsArray)
                    array.put(obj)
                }
                prefs.edit().putString("custom_socks_groups", array.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    private fun putMapToJson(key: String, map: Map<String, String>) {
        val jsonObj = JSONObject()
        for ((k, v) in map) {
            jsonObj.put(k, v)
        }
        prefs.edit().putString(key, jsonObj.toString()).apply()
    }

    // ============ 我的收藏 ============

    /** 有序收藏表: 频道名 -> 来源分类 (LinkedHashMap 保持收藏先后顺序) */
    var favoriteChannels: Map<String, String>
        get() {
            val jsonStr = prefs.getString("favorite_channels", null) ?: return emptyMap()
            return try {
                val array = JSONArray(jsonStr)
                val map = LinkedHashMap<String, String>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("n", "")
                    if (name.isEmpty()) continue
                    map[name] = obj.optString("c", "")
                }
                map
            } catch (e: Exception) {
                e.printStackTrace()
                emptyMap()
            }
        }
        set(value) {
            try {
                val array = JSONArray()
                for ((name, cat) in value) {
                    val obj = JSONObject()
                    obj.put("n", name)
                    obj.put("c", cat)
                    array.put(obj)
                }
                prefs.edit().putString("favorite_channels", array.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    fun isFavorite(channelName: String): Boolean = favoriteChannels.containsKey(channelName)

    /** 长按切换收藏状态; 返回 true = 已收藏, false = 已取消收藏 */
    fun toggleFavorite(channelName: String, sourceCategory: String): Boolean {
        val map = LinkedHashMap(favoriteChannels)
        val nowFavorite: Boolean
        if (map.containsKey(channelName)) {
            map.remove(channelName)
            nowFavorite = false
        } else {
            map[channelName] = sourceCategory
            nowFavorite = true
        }
        favoriteChannels = map
        return nowFavorite
    }

    var lastPlayedUrl: String
        get() = prefs.getString(KEY_LAST_PLAYED_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PLAYED_URL, value).apply()

    var lastCategoryName: String
        get() = prefs.getString("last_category_name", "") ?: ""
        set(value) = prefs.edit().putString("last_category_name", value).apply()

    var lastChannelName: String
        get() = prefs.getString("last_channel_name", "") ?: ""
        set(value) = prefs.edit().putString("last_channel_name", value).apply()

    var lastUrlIndex: Int
        get() = prefs.getInt("last_url_index", 0)
        set(value) = prefs.edit().putInt("last_url_index", value).apply()

    var favorites: Set<String>
        get() {
            val jsonSet = getSetFromJson(KEY_FAVORITES + "_json")
            if (jsonSet != null) return jsonSet
            
            try {
                val oldSet = prefs.getStringSet(KEY_FAVORITES, null)
                if (oldSet != null) {
                    val copy = oldSet.toSet()
                    putSetToJson(KEY_FAVORITES + "_json", copy)
                    prefs.edit().remove(KEY_FAVORITES).apply()
                    return copy
                }
            } catch (e: Exception) {}
            return emptySet()
        }
        set(value) = putSetToJson(KEY_FAVORITES + "_json", value)

    private fun getSetFromJson(key: String): Set<String>? {
        return try {
            val jsonStr = prefs.getString(key, null) ?: return null
            val array = org.json.JSONArray(jsonStr)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            set
        } catch (e: Exception) {
            null
        }
    }

    private fun putSetToJson(key: String, set: Set<String>) {
        val array = org.json.JSONArray()
        set.forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun toggleFavorite(url: String): Boolean {
        val current = favorites.toMutableSet()
        val isAdded = if (current.contains(url)) {
            current.remove(url)
            false
        } else {
            current.add(url)
            true
        }
        favorites = current
        return isAdded
    }
}
