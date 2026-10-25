package com.iptv.tvplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.GestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import com.iptv.tvplayer.server.LocalServer
import com.iptv.tvplayer.proxy.ProxyManager
import com.iptv.tvplayer.utils.KeyUtil

// Update server configuration
val UPDATE_SERVER_URL = "https://gh-proxy.com/https://raw.githubusercontent.com/sfee1212/moyutv/main/update.json"

/**
 * Shared key event bridge between Activity and Compose.
 * Activity-level dispatchKeyEvent guarantees 100% reliable interception
 * regardless of which native View (WebView, SurfaceView) has focus.
 */
object KeyEventBus {
    /** Called on KeyDown. Return true to consume the event. */
    var onKeyDown: ((keyCode: Int) -> Boolean)? = null
    /** Set of key codes currently being consumed (to also eat their KeyUp). */
    val consumedKeys = mutableSetOf<Int>()
}

object InterceptHttpClient {
    val client: okhttp3.OkHttpClient by lazy {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}

class MainActivity : ComponentActivity() {
    
    private val activityConsumedKeys = mutableSetOf<Int>()
    private var localServer: LocalServer? = null
    private var currentUrl = ""
    private var playJob: kotlinx.coroutines.Job? = null
    private lateinit var gestureDetector: GestureDetector

    /** 防止 getResources() 里 updateConfiguration 触发递归 */
    @Volatile
    private var adjustingDensity = false

    private val localFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            importLocalPlaylist(it)
        }
    }

    private lateinit var osdContainer: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var splashImage: ImageView
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvNetworkSpeed: android.widget.TextView
    private lateinit var menuOverlay: LinearLayout
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var btnSettingsTop: android.widget.LinearLayout

    // Bottom EPG OSD Views
    private lateinit var bottomOsdBar: android.view.View
    private lateinit var tvOsdChannelName: android.widget.TextView
    private lateinit var tvOsdEpgCurrent: android.widget.TextView
    private lateinit var tvOsdEpgNext: android.widget.TextView
    private lateinit var pbOsdEpg: android.widget.ProgressBar
    private lateinit var tvOsdEpgElapsed: android.widget.TextView
    private lateinit var tvOsdEpgRemaining: android.widget.TextView
    private lateinit var tvOsdTechSpecs: android.widget.TextView
    private lateinit var tvOsdPlayUrl: android.widget.TextView
    private lateinit var tvOsdClock: android.widget.TextView
    private lateinit var tvOsdDateWeek: android.widget.TextView
    private lateinit var tvOsdLocation: android.widget.TextView
    private lateinit var tvOsdWeatherToday: android.widget.TextView
    private lateinit var tvOsdWeatherTomorrow: android.widget.TextView
    private lateinit var tvOsdBadgeRes: android.widget.TextView
    private lateinit var tvOsdBadgeAudio: android.widget.TextView
    private lateinit var tvOsdBadgeAspect: android.widget.TextView
    private lateinit var tvOsdBadgeCatchup: android.widget.TextView
    private lateinit var tvOsdLunar: android.widget.TextView
    private lateinit var tvOsdDesc: android.widget.TextView
    private lateinit var tvOsdTopWeather: android.widget.TextView
    private lateinit var tvOsdTopChannel: android.widget.TextView
    private lateinit var tvOsdTopBadge: android.widget.TextView
    private lateinit var tvOsdVerticalTitle: android.widget.TextView

    @Volatile private var weatherCity = ""
    @Volatile private var weatherCurrentTemp = ""
    @Volatile private var weatherCurrentDesc = ""
    @Volatile private var weatherTodayMax = ""
    @Volatile private var weatherTodayMin = ""
    @Volatile private var weatherTodayDesc = ""
    @Volatile private var weatherTomorrowMax = ""
    @Volatile private var weatherTomorrowMin = ""
    @Volatile private var weatherTomorrowDesc = ""
    @Volatile private var weatherWindDir = ""
    @Volatile private var weatherWindLevel = -1
    @Volatile private var weatherTomorrowWindLevel = -1
    private val hideOsdRunnable = Runnable { bottomOsdBar.visibility = View.GONE }

    private lateinit var categoryAdapter: com.iptv.tvplayer.ui.CategoryAdapter
    private lateinit var channelAdapter: com.iptv.tvplayer.ui.ChannelAdapter

    private var categories = mutableListOf<String>()
    private var channelsMap = mutableMapOf<String, List<com.iptv.tvplayer.data.Channel>>()

    /** 我的收藏分类名, 固定在主分类第一位 */
    private val FAVORITE_CATEGORY = "我的收藏"

    private lateinit var catchupPanel: android.widget.LinearLayout
    private lateinit var rvCatchupDays: RecyclerView
    private lateinit var rvCatchupPrograms: RecyclerView
    private lateinit var catchupDayAdapter: com.iptv.tvplayer.ui.CatchupDayAdapter
    private lateinit var catchupProgramAdapter: com.iptv.tvplayer.ui.CatchupProgramAdapter
    private lateinit var catchupHintTab: android.view.View
    private var catchupFocusedChannel: com.iptv.tvplayer.data.Channel? = null
    private var playingCatchupTitle: String? = null
    private var playingCatchupProgram: com.iptv.tvplayer.data.EpgProgram? = null
    private var currentCatchupDay: String? = null

    private var playingCategoryIndex = 0
    private var playingChannelIndex = 0
    private var playingUrlIndex = 0
    private var menuFocusedChannel: com.iptv.tvplayer.data.Channel? = null
    private var categorySelectJob: kotlinx.coroutines.Job? = null
    private var loadedCategoryIndex = -1

    private val hideMenuRunnable = Runnable { menuOverlay.visibility = View.GONE }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var settingsOverlayMinimal: android.widget.LinearLayout
    private lateinit var btnSettingsBack: android.widget.LinearLayout
    private lateinit var btnSettingsRefresh: android.widget.LinearLayout
    private lateinit var rvSettingsMinimal: RecyclerView
    private lateinit var settingsMinimalAdapter: com.iptv.tvplayer.ui.SettingsMinimalAdapter

    override fun getResources(): android.content.res.Resources {
        val res = super.getResources()
        if (adjustingDensity) return res
        try {
            adjustingDensity = true
            val metrics = res.displayMetrics
            val config = res.configuration

            // Android TV adaptation: Force scale to match 540p base resolution (standard TV density).
            // This makes everything (fonts, UI) 33% larger than a 720p base.
            val h = Math.min(metrics.widthPixels, metrics.heightPixels).toFloat()
            if (h <= 0f) return res

            val targetDensity = h / 540f
            if (!targetDensity.isFinite() || targetDensity <= 0f) return res

            if (Math.abs(metrics.density - targetDensity) < 0.01f &&
                Math.abs(metrics.scaledDensity - targetDensity) < 0.01f
            ) {
                return res
            }

            val fontScale = if (config.fontScale > 0f) config.fontScale else 1f
            config.densityDpi = (targetDensity * 160f).toInt()
            // 同步 dp 尺寸, 避免 values-sw600dp 等限定符与 density 不一致
            config.screenWidthDp = (metrics.widthPixels / targetDensity).toInt()
            config.screenHeightDp = (metrics.heightPixels / targetDensity).toInt()
            config.smallestScreenWidthDp = (h / targetDensity).toInt()

            metrics.density = targetDensity
            metrics.densityDpi = config.densityDpi
            // 尊重系统字体缩放
            metrics.scaledDensity = targetDensity * fontScale

            // Ignore deprecation warning, updateConfiguration is reliable on older TV OS
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, metrics)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "density adaptation failed: ${t.message}")
        } finally {
            adjustingDensity = false
        }
        return res
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.setLibraries("ffmpegJNI")
            val hasFfmpeg = androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()
            val hasAv3a = androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat("audio/av3a")
            android.util.Log.e("AV3A_TEST", "Ffmpeg available: $hasFfmpeg, AV3A supported: $hasAv3a")
        } catch (e: Exception) {
            android.util.Log.e("AV3A_TEST", "Ffmpeg check failed", e)
        }

        // Ensure screen stays on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContentView(R.layout.activity_main)

        com.iptv.tvplayer.data.SettingsManager.init(this)
        ProxyManager.init(this)
        
        com.iptv.tvplayer.script.ScriptManager.init(this)
        
        // Ensure storage permissions are requested on startup
        checkAndCreateFolders()
        
        com.iptv.tvplayer.proxy.MihomoProxyManager.start(this)
        
        try {
            localServer = LocalServer(this)
            localServer?.start()
        } catch (e: Exception) {}

        playerContainer = findViewById(R.id.player_container)
        osdContainer = findViewById(R.id.osd_container)
        initNumberSelectOverlay()
        splashImage = findViewById(R.id.splash_image)
        tvStatus = findViewById(R.id.loading_text)
        tvNetworkSpeed = findViewById(R.id.tv_network_speed)
        menuOverlay = findViewById(R.id.menu_overlay)
        rvCategories = findViewById(R.id.rv_categories)
        rvChannels = findViewById(R.id.rv_channels)
        
        catchupPanel = findViewById(R.id.catchup_panel)
        catchupHintTab = findViewById(R.id.catchup_hint_tab)
        rvCatchupDays = findViewById(R.id.rv_catchup_days)
        rvCatchupPrograms = findViewById(R.id.rv_catchup_programs)
        
        catchupHintTab.setOnClickListener {
            val channel = menuFocusedChannel
            if (channel != null) {
                showCatchupPanelForChannel(channel)
            }
        }
        btnSettingsTop = findViewById(R.id.btn_settings_top)
        btnSettingsTop.visibility = View.GONE // Hide settings button at the top of the category list

        // Initialize Bottom OSD Views
        bottomOsdBar = findViewById(R.id.bottom_osd_bar)
        tvOsdChannelName = findViewById(R.id.tv_osd_channel_name)
        tvOsdEpgCurrent = findViewById(R.id.tv_osd_epg_current)
        tvOsdEpgNext = findViewById(R.id.tv_osd_epg_next)
        pbOsdEpg = findViewById(R.id.pb_osd_epg)
        tvOsdEpgElapsed = findViewById(R.id.tv_osd_epg_elapsed)
        tvOsdEpgRemaining = findViewById(R.id.tv_osd_epg_remaining)
        tvOsdTechSpecs = findViewById(R.id.tv_osd_tech_specs)
        tvOsdPlayUrl = findViewById(R.id.tv_osd_play_url)
        tvOsdPlayUrl.isSelected = true
        tvOsdClock = findViewById(R.id.tv_osd_clock)
        tvOsdDateWeek = findViewById(R.id.tv_osd_date_week)
        tvOsdLocation = findViewById(R.id.tv_osd_location)
        tvOsdWeatherToday = findViewById(R.id.tv_osd_weather_today)
        tvOsdWeatherTomorrow = findViewById(R.id.tv_osd_weather_tomorrow)
        tvOsdBadgeRes = findViewById(R.id.tv_osd_badge_res)
        tvOsdBadgeAudio = findViewById(R.id.tv_osd_badge_audio)
        tvOsdBadgeAspect = findViewById(R.id.tv_osd_badge_aspect)
        tvOsdBadgeCatchup = findViewById(R.id.tv_osd_badge_catchup)
        tvOsdLunar = findViewById(R.id.tv_osd_lunar)
        tvOsdDesc = findViewById(R.id.tv_osd_desc)
        tvOsdTopWeather = findViewById(R.id.tv_osd_top_weather)
        tvOsdTopChannel = findViewById(R.id.tv_osd_top_channel)
        tvOsdTopBadge = findViewById(R.id.tv_osd_top_badge)
        tvOsdVerticalTitle = findViewById(R.id.tv_osd_vertical_title)

        // Start 1-second OSD clock updates
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (bottomOsdBar.visibility == View.VISIBLE) {
                    refreshOsdClock()
                }
                delay(1000)
            }
        }

        settingsOverlayMinimal = findViewById(R.id.settings_overlay_minimal)
        btnSettingsBack = findViewById(R.id.btn_settings_back)
        btnSettingsRefresh = findViewById(R.id.btn_settings_refresh)
        rvSettingsMinimal = findViewById(R.id.rv_settings_minimal)

        startNetworkSpeedMonitor()
        startTimeAndWeatherMonitor()

        setupRecyclerViews()
        setupSettingsMenu()
        initGestureDetector()
        loadPlaylist()
        checkAppUpdate(manual = false)
    }

    private fun getStreamType(url: String): String {
        val u = url.lowercase(java.util.Locale.US).trim()
        return when {
            u.contains("socks5://") || u.contains("socks://") || u.startsWith("sk://") || u.startsWith("socks5:") || u.contains("socks5=") || u.indexOf("socks5://", 5) >= 0 || u.contains("http_proxy=") || u.contains("http_proxy://") -> "SOCKS"
            u.contains(".m3u8") || u.contains("/hls/") || u.contains("hls") -> "HLS"
            u.contains(".flv") || u.contains("/flv") || u.startsWith("rtmp://") || u.startsWith("rtmpe://") -> "FLV"
            else -> "WEB"
        }
    }

    private fun getGlobalChannelNumber(catIdx: Int, chIdx: Int): Int {
        var globalIndex = 1
        for (i in 0 until catIdx) {
            val cat = categories.getOrNull(i) ?: continue
            val chList = channelsMap[cat] ?: emptyList()
            globalIndex += chList.size
        }
        globalIndex += chIdx
        return globalIndex
    }

    private fun showOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        updateOsdContent()
        bottomOsdBar.visibility = View.VISIBLE
        handler.postDelayed(hideOsdRunnable, 8000)
    }

    private fun translateWeatherDesc(desc: String): Pair<String, String> {
        val d = desc.lowercase().trim()
        return when {
            d.contains("sunny") || d.contains("clear") -> Pair("☀️", "晴")
            d.contains("partly cloudy") -> Pair("⛅", "多云")
            d.contains("cloudy") -> Pair("⛅", "多云")
            d.contains("overcast") -> Pair("☁️", "阴")
            d.contains("mist") || d.contains("fog") || d.contains("haze") -> Pair("🌫", "雾")
            d.contains("thunder") -> Pair("⛈️", "雷阵雨")
            d.contains("rain") || d.contains("drizzle") || d.contains("shower") -> Pair("🌧", "雨")
            d.contains("snow") || d.contains("sleet") || d.contains("blizzard") -> Pair("❄️", "雪")
            else -> Pair("🌤", desc)
        }
    }

    private fun updateOsdContent() {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: ""
        val chList = channelsMap[cat] ?: emptyList()
        val channel = chList.getOrNull(playingChannelIndex) ?: return

        val globalNum = getGlobalChannelNumber(playingCategoryIndex, playingChannelIndex)
        val chNum = String.format(java.util.Locale.US, "%04d", globalNum)

        // 右上角频道号 + 底栏频道名
        tvOsdTopChannel.text = chNum
        tvOsdChannelName.text = channel.name
        tvOsdBadgeCatchup.visibility = if (supportsCatchup(channel)) View.VISIBLE else View.GONE

        // 分辨率 / 画质标签
        val resVal = com.iptv.tvplayer.player.PlayerStateManager.videoResolution.value
        var width = 0
        var height = 0
        val resolutionTag: String
        if (resVal.isNotBlank() && resVal.contains("x")) {
            try {
                val parts = resVal.split("x")
                width = parts[0].toInt()
                height = parts[1].toInt()
            } catch (e: Exception) {}
        }
        resolutionTag = when {
            width >= 3840 || height >= 2160 -> "4K"
            width >= 1920 || height >= 1080 -> "高清"
            channel.name.contains("4K", ignoreCase = true) || channel.name.contains("超清", ignoreCase = true) -> "4K"
            channel.name.contains("HD", ignoreCase = true) || channel.name.contains("高清", ignoreCase = true) -> "高清"
            else -> "标清"
        }
        tvOsdTopBadge.text = resolutionTag
        tvOsdBadgeRes.text = resolutionTag
        tvOsdBadgeAudio.text = com.iptv.tvplayer.player.PlayerStateManager.audioLabel
        tvOsdBadgeAspect.text = "16:9"

        // 当前 / 下一档节目
        val catchupProg = playingCatchupProgram
        val activeProgram: com.iptv.tvplayer.data.EpgProgram?
        if (catchupProg != null) {
            activeProgram = catchupProg
            tvOsdEpgCurrent.text = "正在播放: [回看] ${catchupProg.startTimeStr} - ${catchupProg.endTimeStr} | ${catchupProg.title}"
            tvOsdEpgNext.text = "接下来: --"
        } else {
            val (curProgram, nextProgram) = com.iptv.tvplayer.data.EpgManager.getCurrentAndNextProgram(channel.name)
            activeProgram = curProgram
            tvOsdEpgCurrent.text = if (curProgram != null) {
                "正在播放: ${curProgram.startTimeStr} - ${curProgram.endTimeStr} | ${curProgram.title}"
            } else {
                "正在播放: --"
            }
            tvOsdEpgNext.text = if (nextProgram != null) {
                "接下来: ${nextProgram.startTimeStr} - ${nextProgram.endTimeStr} ${nextProgram.title}"
            } else {
                "接下来: --"
            }
            if (curProgram != null) {
                val now = System.currentTimeMillis()
                val total = curProgram.endMillis - curProgram.startMillis
                pbOsdEpg.progress = if (total > 0) {
                    (((now - curProgram.startMillis).toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
                } else 0
            } else {
                pbOsdEpg.progress = 0
            }
        }
        pbOsdEpg.visibility = if (activeProgram != null) View.VISIBLE else View.GONE
        tvOsdEpgElapsed.visibility = View.GONE
        tvOsdEpgRemaining.visibility = View.GONE

        // 技参数行：线路 · 分辨率 · 音频 · 网络 | 剩余播放
        val remainingMs = activeProgram?.let {
            (it.endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        } ?: 0L
        val line = "线路 ${playingUrlIndex + 1}/${channel.urls.size}"
        val netType = if (currentUrl.contains("ipv6", ignoreCase = true)) "IPV6" else "IPV4"
        val resText = if (resVal.isNotBlank()) resVal else "1920x1080"
        tvOsdTechSpecs.text = "$line · $resText · ${audioCnLabel()} · $netType | 剩余播放: ${formatRemainingTime(remainingMs)}"
        val displayUrl = currentUrl.substringBefore("#headers#").trim()
        tvOsdPlayUrl.text = if (displayUrl.isEmpty()) "播放地址: --" else "播放地址: $displayUrl"
        tvOsdPlayUrl.isSelected = true

        // 节目简介（EPG 暂无简介数据）
        tvOsdDesc.text = "暂无描述信息"

        // 左侧竖排节目名
        val title = activeProgram?.title ?: channel.name
        tvOsdVerticalTitle.text = title.take(16).toCharArray().joinToString("\n")

        refreshOsdClock()
    }

    /** 音频标签转中文：AAC Stereo -> 立体声 */
    private fun audioCnLabel(): String {
        val label = com.iptv.tvplayer.player.PlayerStateManager.audioLabel
        return when {
            label.contains("Stereo") -> "立体声"
            label.contains("Mono") -> "单声道"
            else -> label
        }
    }

    /** 剩余时间格式化：7小时13分钟 */
    private fun formatRemainingTime(ms: Long): String {
        val totalMinutes = (ms / 60000).coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            totalMinutes <= 0 -> "--"
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
    }

    /** 刷新 OSD 时钟 / 日期 / 农历 / 天气 */
    private fun refreshOsdClock() {
        val now = java.util.Date()
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINESE)
        val dateFormat = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINESE)
        val dayFormat = java.text.SimpleDateFormat("EEEE", java.util.Locale.CHINESE)
        tvOsdClock.text = timeFormat.format(now)
        tvOsdDateWeek.text = "${dateFormat.format(now)} ${dayFormat.format(now)}"

        val lunar = com.iptv.tvplayer.utils.LunarCalendar.fromDate(now)
        tvOsdLunar.text = lunar.fullText()
        tvOsdTopWeather.text = buildTopWeatherLine(lunar.shortText())
        updateWeatherViews()
    }

    /** 右上角天气摘要：惠州 农历九月十六 30°/23° 多云转晴 */
    private fun buildTopWeatherLine(lunarShort: String): String {
        val parts = mutableListOf<String>()
        if (weatherCity.isNotEmpty()) parts.add(weatherCity)
        if (lunarShort.isNotEmpty()) parts.add(lunarShort)
        if (weatherTodayMax.isNotEmpty() || weatherTodayMin.isNotEmpty()) {
            parts.add("${weatherTodayMax}°/${weatherTodayMin}°")
        }
        if (weatherTodayDesc.isNotEmpty()) parts.add(weatherTodayDesc)
        return parts.joinToString(" ")
    }

    /** 底栏右侧天气两行 */
    private fun updateWeatherViews() {
        tvOsdLocation.visibility = View.GONE

        val todayParts = mutableListOf<String>()
        if (weatherCity.isNotEmpty()) todayParts.add(weatherCity)
        if (weatherCurrentTemp.isNotEmpty()) todayParts.add("${weatherCurrentTemp}°C")
        if (weatherCurrentDesc.isNotEmpty()) todayParts.add(weatherCurrentDesc)
        if (weatherTodayMin.isNotEmpty() || weatherTodayMax.isNotEmpty()) {
            todayParts.add("${weatherTodayMin}°~${weatherTodayMax}°")
        }
        if (weatherWindDir.isNotEmpty()) todayParts.add(weatherWindDir)
        if (weatherWindLevel >= 0) todayParts.add("${weatherWindLevel}级")
        if (todayParts.isEmpty()) {
            tvOsdWeatherToday.visibility = View.GONE
        } else {
            tvOsdWeatherToday.text = todayParts.joinToString(" ")
            tvOsdWeatherToday.visibility = View.VISIBLE
        }

        val tomorrowParts = mutableListOf<String>()
        if (weatherTomorrowMax.isNotEmpty() || weatherTomorrowMin.isNotEmpty()) {
            tomorrowParts.add("明天 ${weatherTomorrowMax}°/${weatherTomorrowMin}°")
        }
        if (weatherTomorrowDesc.isNotEmpty()) tomorrowParts.add(weatherTomorrowDesc)
        if (weatherTomorrowWindLevel in 0..3) {
            tomorrowParts.add("微风 <3级")
        } else if (weatherTomorrowWindLevel > 3) {
            tomorrowParts.add("${weatherTomorrowWindLevel}级")
        }
        if (tomorrowParts.isEmpty()) {
            tvOsdWeatherTomorrow.visibility = View.GONE
        } else {
            tvOsdWeatherTomorrow.text = tomorrowParts.joinToString(" ")
            tvOsdWeatherTomorrow.visibility = View.VISIBLE
        }
    }

    /** 风向英文转中文 */
    private fun translateWindDir(point: String): String {
        val p = point.uppercase(java.util.Locale.US)
        return when {
            p.isEmpty() -> ""
            p.contains("N") && p.contains("E") -> "东北风"
            p.contains("N") && p.contains("W") -> "西北风"
            p.contains("S") && p.contains("E") -> "东南风"
            p.contains("S") && p.contains("W") -> "西南风"
            p.startsWith("N") -> "北风"
            p.startsWith("S") -> "南风"
            p.startsWith("E") -> "东风"
            p.startsWith("W") -> "西风"
            else -> ""
        }
    }

    /** 风速(km/h)转蒲福风级 */
    private fun windLevelOf(kmph: Int): Int = when {
        kmph <= 1 -> 0
        kmph <= 5 -> 1
        kmph <= 11 -> 2
        kmph <= 19 -> 3
        kmph <= 28 -> 4
        kmph <= 38 -> 5
        kmph <= 49 -> 6
        kmph <= 61 -> 7
        else -> 8
    }

    private fun startNetworkSpeedMonitor() {
        tvNetworkSpeed.visibility = if (com.iptv.tvplayer.data.SettingsManager.showNetworkSpeed) View.VISIBLE else View.GONE
        lifecycleScope.launch(Dispatchers.Main) {
            var lastRxBytes = try {
                android.net.TrafficStats.getTotalRxBytes()
            } catch (_: Throwable) {
                android.net.TrafficStats.UNSUPPORTED.toLong()
            }
            while (isActive) {
                delay(1000)
                try {
                    val currentRxBytes = android.net.TrafficStats.getTotalRxBytes()
                    if (currentRxBytes == android.net.TrafficStats.UNSUPPORTED.toLong() ||
                        lastRxBytes == android.net.TrafficStats.UNSUPPORTED.toLong()
                    ) {
                        tvNetworkSpeed.text = "--"
                    } else {
                        val diff = (currentRxBytes - lastRxBytes).coerceAtLeast(0L)
                        val speedStr = when {
                            diff >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", diff / (1024f * 1024f))
                            diff >= 1024 -> String.format(java.util.Locale.US, "%.1f KB/s", diff / 1024f)
                            else -> "$diff B/s"
                        }
                        tvNetworkSpeed.text = speedStr
                    }
                    lastRxBytes = currentRxBytes
                } catch (t: Throwable) {
                    tvNetworkSpeed.text = "--"
                }
            }
        }
    }

    private fun openTrustAllConnection(url: java.net.URL): java.net.HttpURLConnection {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, java.security.SecureRandom())
        val conn = url.openConnection() as java.net.HttpURLConnection
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    private fun startTimeAndWeatherMonitor() {
        // Weather Updater
        lifecycleScope.launch(Dispatchers.IO) {
            while(isActive) {
                try {
                    // Step 1: Geolocation
                    var city = ""
                    // Try pconline first (accurate for domestic IPs)
                    try {
                        val geoUrl = java.net.URL("https://whois.pconline.com.cn/ipJson.jsp?json=true")
                        val conn = geoUrl.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        val res = conn.inputStream.bufferedReader(java.nio.charset.Charset.forName("GBK")).use { it.readText() }
                        val json = org.json.JSONObject(res)
                        val c = json.optString("city", "").replace("?", "")
                        if (c.isNotEmpty()) {
                            city = c
                        }
                    } catch (e: Exception) { }

                    // Fallback to ip-api.com
                    if (city.isEmpty()) {
                        try {
                            val geoUrl = java.net.URL("http://ip-api.com/json?lang=zh-CN")
                            val conn = geoUrl.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            val res = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = org.json.JSONObject(res)
                            if (json.optString("status") == "success") {
                                val c = json.optString("city", "").replace("?", "")
                                if (c.isNotEmpty()) {
                                    city = c
                                }
                            }
                        } catch (e: Exception) { }
                    }

                    // Step 2: Weather JSON
                    val encodedCity = if (city.isNotEmpty()) java.net.URLEncoder.encode(city, "UTF-8") else ""
                    val weatherUrl = java.net.URL(if (encodedCity.isNotEmpty()) "http://wttr.in/$encodedCity?format=j1" else "http://wttr.in/?format=j1")
                    val conn = weatherUrl.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val res = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    
                    if (res.isNotEmpty() && !res.contains("html") && !res.contains("Error")) {
                        val json = org.json.JSONObject(res)
                        val weatherArr = json.optJSONArray("weather")
                        
                        // Extract actual city name from wttr.in if we didn't have one
                        if (city.isEmpty()) {
                            try {
                                val nearestArea = json.optJSONArray("nearest_area")?.optJSONObject(0)
                                val areaName = nearestArea?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value", "")
                                if (!areaName.isNullOrEmpty()) {
                                    city = areaName
                                }
                            } catch (e: Exception) {}
                        }
                        
                        val todayObj = weatherArr?.optJSONObject(0)
                        weatherTodayMax = todayObj?.optString("maxtempC", "") ?: ""
                        weatherTodayMin = todayObj?.optString("mintempC", "") ?: ""
                        val todayDescEn = todayObj?.optJSONArray("hourly")?.optJSONObject(4)?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "") ?: ""
                        weatherTodayDesc = translateWeatherDesc(todayDescEn).second

                        val tomorrowObj = weatherArr?.optJSONObject(1)
                        weatherTomorrowMax = tomorrowObj?.optString("maxtempC", "") ?: ""
                        weatherTomorrowMin = tomorrowObj?.optString("mintempC", "") ?: ""
                        val tomorrowDescEn = tomorrowObj?.optJSONArray("hourly")?.optJSONObject(4)?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "") ?: ""
                        weatherTomorrowDesc = translateWeatherDesc(tomorrowDescEn).second
                        weatherTomorrowWindLevel = windLevelOf(
                            tomorrowObj?.optJSONArray("hourly")?.optJSONObject(4)
                                ?.optString("windspeedKmph", "0")?.toIntOrNull() ?: 0
                        )

                        val currentObj = json.optJSONObject("current_condition")
                        weatherCurrentTemp = currentObj?.optString("temp_C", "") ?: ""
                        val currentDescEn = currentObj?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "") ?: ""
                        weatherCurrentDesc = translateWeatherDesc(currentDescEn).second
                        weatherWindDir = translateWindDir(currentObj?.optString("winddir16Point", "") ?: "")
                        weatherWindLevel = windLevelOf(
                            currentObj?.optString("windspeedKmph", "0")?.toIntOrNull() ?: 0
                        )

                        weatherCity = city
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && bottomOsdBar.visibility == View.VISIBLE) {
                                refreshOsdClock()
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Weather", "HTTP weather fetch failed: ${e.message}")
                }
                delay(30 * 60 * 1000L) // 30 minutes
            }
        }
    }

    private var currentMenuCategoryIndex = 0

    private lateinit var tvSettingsTitle: android.widget.TextView
    private var settingsLevel = 0 // 0: Root, 1: Sub, 2: EPG, 3: Decoder, 4: Display, 5: Timeout
    private var currentSocksGroupEdit = ""
    private var currentCategoryProxyEdit = ""

    fun showSettingsLevel(level: Int, targetIdentifier: String? = null) {
        settingsLevel = level
        val items = mutableListOf<com.iptv.tvplayer.ui.SettingItem>()
        var selectedPos = 0

        when (level) {
            0 -> {
                tvSettingsTitle.text = "系统设置"
                btnSettingsRefresh.visibility = View.GONE
                
                // Get active sub name
                val actSubUrls = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls
                val subs = com.iptv.tvplayer.data.SettingsManager.subscriptions
                val actSubName = if (actSubUrls.size == 1) {
                    subs.find { it.url == actSubUrls.first() }?.name ?: "内置节目"
                } else {
                    subs.filter { actSubUrls.contains(it.url) }.joinToString { it.name }
                }
                items.add(com.iptv.tvplayer.ui.SettingItem("订阅源配置", "当前: $actSubName (已启用${actSubUrls.size}个)", false, true, "menu_sub"))
                val actEpgUrl = com.iptv.tvplayer.data.SettingsManager.activeEpgUrl
                val actEpgName = com.iptv.tvplayer.data.SettingsManager.epgs.find { it.url == actEpgUrl }?.name ?: "未设置"
                items.add(com.iptv.tvplayer.ui.SettingItem("EPG源配置", "当前: $actEpgName", false, true, "menu_epg"))
                
                val dec = com.iptv.tvplayer.data.SettingsManager.decoderType
                val decodeMode = com.iptv.tvplayer.data.SettingsManager.decodeMode
                val decText = if (dec == "MPV") if (decodeMode == "software") "MPV软解" else "MPV硬解" else if (decodeMode == "software") "EXO软解" else "EXO硬解"
                items.add(com.iptv.tvplayer.ui.SettingItem("解码设置", "当前: $decText", false, true, "menu_decoder"))
                
                val kernelStatus = if (com.iptv.tvplayer.data.SettingsManager.enableTbsX5) "腾讯X5内核" else "系统极速内核"
                items.add(com.iptv.tvplayer.ui.SettingItem("WebView设置", "当前: $kernelStatus", false, true, "menu_kernel"))
                
                items.add(com.iptv.tvplayer.ui.SettingItem("显示设置", "配置画面、台标等", false, true, "menu_display_settings"))
                
                if (categories.isNotEmpty()) {
                    val cat = categories.getOrNull(playingCategoryIndex)
                    val channel = channelsMap[cat]?.getOrNull(playingChannelIndex)
                    if (channel != null && channel.urls.size > 1) {
                        items.add(com.iptv.tvplayer.ui.SettingItem("当前频道线路", "当前: 线路 ${playingUrlIndex + 1}/${channel.urls.size}", false, true, "menu_switch_url"))
                    }
                }
                
                items.add(com.iptv.tvplayer.ui.SettingItem("高级设置", "配置代理/请求头等", false, true, "menu_advanced"))
                
                items.add(com.iptv.tvplayer.ui.SettingItem("检查更新", "当前版本: ${getCurrentVersionName()}", false, true, "menu_update"))
            }
            1 -> {
                tvSettingsTitle.text = "设置 / 订阅源"
                btnSettingsRefresh.visibility = View.VISIBLE
                
                val subs = com.iptv.tvplayer.data.SettingsManager.subscriptions
                val actSubUrls = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls
                subs.forEachIndexed { index, sub ->
                    val isChecked = actSubUrls.contains(sub.url)
                    if (isChecked) selectedPos = index
                    val tag = if (sub.url.startsWith("file://")) "本地" else "远程"
                    items.add(com.iptv.tvplayer.ui.SettingItem(sub.name, tag, isChecked, false, sub.url))
                }
                items.add(com.iptv.tvplayer.ui.SettingItem("添加其他订阅源", null, false, false, "add_sub"))
            }
            2 -> {
                tvSettingsTitle.text = "设置 / EPG"
                btnSettingsRefresh.visibility = View.GONE
                
                val epgs = com.iptv.tvplayer.data.SettingsManager.epgs
                epgs.forEachIndexed { index, epg ->
                    val isChecked = (epg.url == com.iptv.tvplayer.data.SettingsManager.epgUrl)
                    if (isChecked) selectedPos = index
                    items.add(com.iptv.tvplayer.ui.SettingItem(epg.name, null, isChecked, false, epg.url))
                }
                items.add(com.iptv.tvplayer.ui.SettingItem("自定义其他EPG", null, false, false, "add_epg"))
            }
            3 -> {
                tvSettingsTitle.text = "设置 / 解码设置"
                btnSettingsRefresh.visibility = View.GONE
                
                val dec = com.iptv.tvplayer.data.SettingsManager.decoderType
                val mode = com.iptv.tvplayer.data.SettingsManager.decodeMode
                
                items.add(com.iptv.tvplayer.ui.SettingItem("MPV硬解", "", dec == "MPV" && mode == "hardware", false, "MPV_hardware"))
                items.add(com.iptv.tvplayer.ui.SettingItem("MPV软解", "", dec == "MPV" && mode == "software", false, "MPV_software"))
                items.add(com.iptv.tvplayer.ui.SettingItem("EXO硬解", "", dec == "EXO" && mode == "hardware", false, "EXO_hardware"))
                items.add(com.iptv.tvplayer.ui.SettingItem("EXO软解", "", dec == "EXO" && mode == "software", false, "EXO_software"))
                
                selectedPos = when {
                    dec == "MPV" && mode == "hardware" -> 0
                    dec == "MPV" && mode == "software" -> 1
                    dec == "EXO" && mode == "hardware" -> 2
                    dec == "EXO" && mode == "software" -> 3
                    else -> 0
                }
            }
            4 -> {
                tvSettingsTitle.text = "设置 / 画面显示"
                btnSettingsRefresh.visibility = View.GONE
                
                val aspect = com.iptv.tvplayer.data.SettingsManager.aspectRatio
                items.add(com.iptv.tvplayer.ui.SettingItem("填充", "拉伸铺满全屏", aspect == "填充", false, "填充"))
                items.add(com.iptv.tvplayer.ui.SettingItem("填充", "拉伸铺满全屏", aspect == "填充", false, "填充"))
                items.add(com.iptv.tvplayer.ui.SettingItem("16:9", "拉伸至16:9 比例", aspect == "16:9", false, "16:9"))
                items.add(com.iptv.tvplayer.ui.SettingItem("裁剪", "等比铺满裁剪余下", aspect == "裁剪", false, "裁剪"))
                selectedPos = when (aspect) {
                    "填充" -> 1
                    "16:9" -> 2
                    "裁剪" -> 3
                    else -> 0
                }
            }
            5 -> {
                tvSettingsTitle.text = "设置 / 超时时间"
                btnSettingsRefresh.visibility = View.GONE
                
                val timeout = com.iptv.tvplayer.data.SettingsManager.timeoutSeconds
                val options = listOf(5, 10, 15, 20, 25, 30)
                options.forEachIndexed { index, t ->
                    if (t == timeout) selectedPos = index
                    items.add(com.iptv.tvplayer.ui.SettingItem("${t} 秒", null, t == timeout, false, t.toString()))
                }
            }
            6 -> {
                tvSettingsTitle.text = "设置 / 解码模式"
                btnSettingsRefresh.visibility = View.GONE
                
                val mode = com.iptv.tvplayer.data.SettingsManager.decodeMode
                items.add(com.iptv.tvplayer.ui.SettingItem("硬件解码", "推荐（硬解较流畅，旧设备可能假死）", mode == "hardware", false, "hardware"))
                items.add(com.iptv.tvplayer.ui.SettingItem("软件解码", "兼容（软解防假死/崩溃，消耗CPU）", mode == "software", false, "software"))
                if (mode == "software") selectedPos = 1
            }
            7 -> {
                tvSettingsTitle.text = "高级设置"
                btnSettingsRefresh.visibility = View.GONE
                
                val timeout = com.iptv.tvplayer.data.SettingsManager.timeoutSeconds
                items.add(com.iptv.tvplayer.ui.SettingItem("超时时间", "当前: ${timeout}秒", false, true, "menu_timeout"))
                
                val proxyStatus = if (com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl.trim().isNotEmpty()) "已配置" else "未配置"
                items.add(com.iptv.tvplayer.ui.SettingItem("代理订阅", proxyStatus, false, true, "menu_proxy_sub"))
                items.add(com.iptv.tvplayer.ui.SettingItem("配置分类代理", "指定哪些分类走代理池", false, true, "menu_proxy_cats"))
                items.add(com.iptv.tvplayer.ui.SettingItem("自定义SOCKS5代理", "管理代理池及分组", false, true, "menu_socks5_groups"))
                items.add(com.iptv.tvplayer.ui.SettingItem("断流重连", if (com.iptv.tvplayer.data.SettingsManager.autoReconnect) "开启" else "关闭", false, false, "menu_reconnect"))
                items.add(com.iptv.tvplayer.ui.SettingItem("UserAgent", "点击配置", false, true, "menu_user_agent"))
                items.add(com.iptv.tvplayer.ui.SettingItem("Headers", "点击配置", false, true, "menu_headers"))
            }
            11 -> {
                tvSettingsTitle.text = "设置 / 显示设置"
                btnSettingsRefresh.visibility = View.GONE
                
                val aspect = com.iptv.tvplayer.data.SettingsManager.aspectRatio
                items.add(com.iptv.tvplayer.ui.SettingItem("画面比例", "当前: $aspect", false, true, "menu_display"))
                
                val showNumStr = if (com.iptv.tvplayer.data.SettingsManager.showChannelNumber) "开启" else "关闭"
                items.add(com.iptv.tvplayer.ui.SettingItem("显示序号", showNumStr, false, false, "menu_show_num"))
                
                val showLogoStr = if (com.iptv.tvplayer.data.SettingsManager.showChannelLogo) "开启" else "关闭"
                items.add(com.iptv.tvplayer.ui.SettingItem("频道台标", showLogoStr, false, false, "menu_show_logo"))
                
                val showSpeed = com.iptv.tvplayer.data.SettingsManager.showNetworkSpeed
                items.add(com.iptv.tvplayer.ui.SettingItem("显示速度", if (showSpeed) "正常" else "超高速", false, false, "menu_show_speed"))
            }
            8 -> {
                tvSettingsTitle.text = "设置 / 分类代理"
                btnSettingsRefresh.visibility = View.GONE
                
                val proxyMap = com.iptv.tvplayer.data.SettingsManager.categoryProxyMap
                categories.forEachIndexed { index, cat ->
                    val proxyTarget = proxyMap[cat] ?: "直连 (不代理)"
                    items.add(com.iptv.tvplayer.ui.SettingItem(cat, proxyTarget, false, true, "catproxy_$cat"))
                }
            }
            12 -> {
                tvSettingsTitle.text = "选择代理: $currentCategoryProxyEdit"
                btnSettingsRefresh.visibility = View.GONE
                val proxyMap = com.iptv.tvplayer.data.SettingsManager.categoryProxyMap
                val currentTarget = proxyMap[currentCategoryProxyEdit]
                
                items.add(com.iptv.tvplayer.ui.SettingItem("直连 (不代理)", "", currentTarget == null, false, "selproxy_null"))
                items.add(com.iptv.tvplayer.ui.SettingItem("代理池", "订阅节点", currentTarget == "代理池", false, "selproxy_代理"))
                
                val groups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups
                groups.forEach { g ->
                    items.add(com.iptv.tvplayer.ui.SettingItem(g.name, "自定义SOCKS5", currentTarget == g.name, false, "selproxy_${g.name}"))
                }
            }
            13 -> {
                tvSettingsTitle.text = "设置 / 自定义SOCKS5分组"
                btnSettingsRefresh.visibility = View.GONE
                items.add(com.iptv.tvplayer.ui.SettingItem("新建代理分组", "点击创建新分组", false, true, "socks_add"))
                val groups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups
                groups.forEachIndexed { idx, g ->
                    items.add(com.iptv.tvplayer.ui.SettingItem(g.name, "${g.ips.size}个IP - 点击编辑", false, true, "socks_group_${g.name}"))
                }
            }
            14 -> {
                tvSettingsTitle.text = "编辑分组: $currentSocksGroupEdit"
                btnSettingsRefresh.visibility = View.GONE
                val group = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.find { it.name == currentSocksGroupEdit }
                if (group != null) {
                    items.add(com.iptv.tvplayer.ui.SettingItem("自定义IP端口", group.ips.joinToString("/").takeIf { it.isNotEmpty() } ?: "自定义端口", false, true, "socks_edit_ips"))
                    items.add(com.iptv.tvplayer.ui.SettingItem("删除分组", "删除当前分组", false, true, "socks_del_group"))
                }
            }
            15 -> {
                tvSettingsTitle.text = "设置 / 代理订阅"
                btnSettingsRefresh.visibility = View.GONE
                items.add(com.iptv.tvplayer.ui.SettingItem("配置代理链接", com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl.takeIf{it.isNotEmpty()}?:"未配置", false, true, "menu_proxy_sub_url"))
                items.add(com.iptv.tvplayer.ui.SettingItem("节点测速", "上次延迟", false, true, "menu_proxy_nodes"))
            }
            16 -> {
                tvSettingsTitle.text = "获取节点.."
                btnSettingsRefresh.visibility = View.VISIBLE
                
                lifecycleScope.launch {
                    val nodes = com.iptv.tvplayer.proxy.MihomoApiClient.getProxies()
                    if (settingsLevel == 16) {
                        tvSettingsTitle.text = "代理节点选择 (${nodes.size})"
                        items.clear()
                        nodes.forEach { node ->
                            items.add(com.iptv.tvplayer.ui.SettingItem(node.name, "点击测速使用，上次延迟:${if (node.delay>0) node.delay.toString()+"ms" else "-"}", false, true, "mihomo_node_${node.name}"))
                        }
                        settingsMinimalAdapter.updateData(items, 0)
                    }
                }
            }
            10 -> {
                tvSettingsTitle.text = "内核管理"
                btnSettingsRefresh.visibility = View.GONE
                val currentTbs = com.iptv.tvplayer.data.SettingsManager.enableTbsX5
                items.add(com.iptv.tvplayer.ui.SettingItem("系统原生极速内核", if (!currentTbs) "已开启" else "", false, false, "kernel_sys"))
                items.add(com.iptv.tvplayer.ui.SettingItem("腾讯X5插件安装 (已安装/未安装)", if (currentTbs) "使用系统内核" else "手动安装/自动忽略", false, false, "kernel_x5"))
                selectedPos = if (currentTbs) 1 else 0
            }
        }
        
        if (targetIdentifier != null) {
            val idx = items.indexOfFirst { it.identifier == targetIdentifier }
            if (idx != -1) {
                selectedPos = idx
            }
        }
        
        settingsMinimalAdapter.updateData(items, selectedPos)
        
        val count = settingsMinimalAdapter.itemCount
        val safeIndex = if (selectedPos in 0 until count) selectedPos else 0
        rvSettingsMinimal.scrollToPosition(safeIndex)
        
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                val v = rvSettingsMinimal.layoutManager?.findViewByPosition(safeIndex)
                if (v != null) {
                    v.requestFocus()
                } else if (attempts < 15) {
                    attempts++
                    handler.postDelayed(this, 20)
                } else {
                    val firstChild = rvSettingsMinimal.layoutManager?.getChildAt(0)
                    firstChild?.requestFocus()
                }
            }
        }
        rvSettingsMinimal.post(runnable)
    }

    private fun setupSettingsMenu() {
        tvSettingsTitle = findViewById(R.id.tv_settings_title)
        rvSettingsMinimal.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        settingsMinimalAdapter = com.iptv.tvplayer.ui.SettingsMinimalAdapter(
            emptyList(),
            { pos, item ->
                when (settingsLevel) {
                    0 -> {
                        when (item.identifier) {
                            "menu_sub" -> showSettingsLevel(1)
                            "menu_epg" -> showSettingsLevel(2)
                            "menu_decoder" -> showSettingsLevel(3)
                            "menu_kernel" -> showSettingsLevel(10)
                            "menu_display_settings" -> showSettingsLevel(11)
                            "menu_update" -> checkAppUpdate(manual = true)
                            "menu_switch_url" -> {
                                switchUrl(1)
                                showSettingsLevel(0, "menu_switch_url")
                            }
                            "menu_advanced" -> showSettingsLevel(7)
                        }
                    }
                    1 -> {
                        if (item.identifier == "add_sub") {
                            showAddSubscriptionDialog()
                        } else {
                            val currentActiveUrls = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls.toMutableSet()
                            if (currentActiveUrls.contains(item.identifier)) {
                                if (currentActiveUrls.size > 1) {
                                    currentActiveUrls.remove(item.identifier)
                                    com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls = currentActiveUrls
                                    showSettingsLevel(1) // refresh checkmarks
                                    loadPlaylist() // load playlist in background
                                } else {
                                    android.widget.Toast.makeText(this@MainActivity, "至少需要保留一个订阅源", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                currentActiveUrls.add(item.identifier)
                                com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls = currentActiveUrls
                                showSettingsLevel(1) // refresh checkmarks
                                loadPlaylist() // load playlist in background
                            }
                        }
                    }
                    2 -> {
                        if (item.identifier == "add_epg") {
                            showAddEpgDialog()
                        } else {
                            com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = item.identifier
                            showSettingsLevel(2)
                            android.widget.Toast.makeText(this, "EPG已切", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        when (item.identifier) {
                            "MPV_hardware" -> {
                                com.iptv.tvplayer.data.SettingsManager.decoderType = "MPV"
                                com.iptv.tvplayer.data.SettingsManager.decodeMode = "hardware"
                            }
                            "MPV_software" -> {
                                com.iptv.tvplayer.data.SettingsManager.decoderType = "MPV"
                                com.iptv.tvplayer.data.SettingsManager.decodeMode = "software"
                            }
                            "EXO_hardware" -> {
                                com.iptv.tvplayer.data.SettingsManager.decoderType = "EXO"
                                com.iptv.tvplayer.data.SettingsManager.decodeMode = "hardware"
                            }
                            "EXO_software" -> {
                                com.iptv.tvplayer.data.SettingsManager.decoderType = "EXO"
                                com.iptv.tvplayer.data.SettingsManager.decodeMode = "software"
                            }
                        }
                        showSettingsLevel(3)
                        android.widget.Toast.makeText(this, "解码设置已切换为 ${item.name}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        com.iptv.tvplayer.data.SettingsManager.aspectRatio = item.identifier
                        showSettingsLevel(4)
                        android.widget.Toast.makeText(this, "画面已切换至${item.identifier}", android.widget.Toast.LENGTH_SHORT).show()
                        com.iptv.tvplayer.player.NativePlayerManager.updateAspectRatio()
                        for (i in 0 until playerContainer.childCount) {
                            val child = playerContainer.getChildAt(i)
                            if (child is com.tencent.smtt.sdk.WebView) {
                                val jsVal = when (item.identifier) {
                                    "填充" -> 3
                                    "16:9" -> 1
                                    "裁剪" -> 2
                                    else -> 0
                                }
                                child.evaluateJavascript("if (window.setscale) { window.setscale($jsVal); }", null)
                            }
                        }
                    }
                    5 -> {
                        com.iptv.tvplayer.data.SettingsManager.timeoutSeconds = item.identifier.toIntOrNull() ?: 15
                        showSettingsLevel(5)
                        android.widget.Toast.makeText(this, "超时时间已切换为 ${item.identifier}秒", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        com.iptv.tvplayer.data.SettingsManager.decodeMode = item.identifier
                        showSettingsLevel(6)
                        val modeName = if (item.identifier == "software") "软件解码" else "硬件解码"
                        android.widget.Toast.makeText(this, "解码模式已切换为 $modeName", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        when (item.identifier) {
                            "menu_timeout" -> showSettingsLevel(5)
                            "menu_proxy_sub" -> showSettingsLevel(15)
                            "menu_proxy_cats" -> showSettingsLevel(8)
                            "menu_socks5_groups" -> showSettingsLevel(13)
                            "menu_reconnect" -> {
                                com.iptv.tvplayer.data.SettingsManager.autoReconnect = !com.iptv.tvplayer.data.SettingsManager.autoReconnect
                                showSettingsLevel(7, "menu_reconnect")
                            }
                            "menu_user_agent" -> showInputDialog("UserAgent")
                            "menu_headers" -> showInputDialog("Headers")
                        }
                    }
                    11 -> {
                        when (item.identifier) {
                            "menu_display" -> showSettingsLevel(4)
                            "menu_show_num" -> {
                                val newVal = !com.iptv.tvplayer.data.SettingsManager.showChannelNumber
                                com.iptv.tvplayer.data.SettingsManager.showChannelNumber = newVal
                                showSettingsLevel(11, "menu_show_num")
                                channelAdapter.notifyDataSetChanged()
                            }
                            "menu_show_logo" -> {
                                com.iptv.tvplayer.data.SettingsManager.showChannelLogo = !com.iptv.tvplayer.data.SettingsManager.showChannelLogo
                                showSettingsLevel(11, "menu_show_logo")
                            }
                            "menu_show_speed" -> {
                                val newVal = !com.iptv.tvplayer.data.SettingsManager.showNetworkSpeed
                                com.iptv.tvplayer.data.SettingsManager.showNetworkSpeed = newVal
                                showSettingsLevel(11, "menu_show_speed")
                                tvNetworkSpeed.visibility = if (newVal) View.VISIBLE else View.GONE
                            }
                        }
                    }
                    8 -> {
                        if (item.identifier.startsWith("catproxy_")) {
                            currentCategoryProxyEdit = item.identifier.removePrefix("catproxy_")
                            showSettingsLevel(12)
                        }
                    }
                    12 -> {
                        if (item.identifier.startsWith("selproxy_")) {
                            val target = item.identifier.removePrefix("selproxy_")
                            val map = com.iptv.tvplayer.data.SettingsManager.categoryProxyMap.toMutableMap()
                            if (target == "null") {
                                map.remove(currentCategoryProxyEdit)
                            } else {
                                map[currentCategoryProxyEdit] = target
                            }
                            com.iptv.tvplayer.data.SettingsManager.categoryProxyMap = map
                            loadPlaylist() // re-apply proxy URLs
                            showSettingsLevel(8, "catproxy_$currentCategoryProxyEdit")
                        }
                    }
                    13 -> {
                        if (item.identifier == "socks_add") {
                            showInputDialog("SOCKS5组名")
                        } else if (item.identifier.startsWith("socks_group_")) {
                            currentSocksGroupEdit = item.identifier.removePrefix("socks_group_")
                            showSettingsLevel(14)
                        }
                    }
                    14 -> {
                        if (item.identifier == "socks_edit_ips") {
                            showInputDialog("SOCKS5_IP列表")
                        } else if (item.identifier == "socks_del_group") {
                            val groups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.toMutableList()
                            groups.removeAll { it.name == currentSocksGroupEdit }
                            com.iptv.tvplayer.data.SettingsManager.customSocksGroups = groups
                            val map = com.iptv.tvplayer.data.SettingsManager.categoryProxyMap.toMutableMap()
                            map.entries.removeIf { it.value == currentSocksGroupEdit }
                            com.iptv.tvplayer.data.SettingsManager.categoryProxyMap = map
                            showSettingsLevel(13)
                        }
                    }
                    15 -> {
                        if (item.identifier == "menu_proxy_sub_url") {
                            showInputDialog("代理订阅")
                        } else if (item.identifier == "menu_proxy_nodes") {
                            showSettingsLevel(16)
                        }
                    }
                    16 -> {
                        if (item.identifier.startsWith("mihomo_node_")) {
                            val nodeName = item.identifier.removePrefix("mihomo_node_")
                            android.widget.Toast.makeText(this@MainActivity, "正在测试延迟并切换..", android.widget.Toast.LENGTH_SHORT).show()
                            lifecycleScope.launch {
                                val delay = com.iptv.tvplayer.proxy.MihomoApiClient.testDelay(nodeName)
                                if (delay > 0) {
                                    val ok = com.iptv.tvplayer.proxy.MihomoApiClient.selectProxy(nodeName)
                                    if (ok) {
                                        android.widget.Toast.makeText(this@MainActivity, "已成功切换到节点: $nodeName ($delay ms)", android.widget.Toast.LENGTH_SHORT).show()
                                        showSettingsLevel(16) // Refresh
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity, "节点测速超时或不可用", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(this@MainActivity, "节点测速超时或不可用", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    10 -> {
                        when (item.identifier) {
                            "kernel_sys" -> {
                                com.iptv.tvplayer.data.SettingsManager.enableTbsX5 = false
                                android.widget.Toast.makeText(this@MainActivity, "已切换为系统极速内核，重启后生效", android.widget.Toast.LENGTH_LONG).show()
                                showSettingsLevel(10, "kernel_sys")
                            }
                            "kernel_x5" -> {
                                if (com.iptv.tvplayer.data.SettingsManager.enableTbsX5) {
                                    android.widget.Toast.makeText(this@MainActivity, "当前已是X5内核", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    showX5DownloadDialog()
                                }
                            }
                        }
                    }
                }
            },
            { pos, item ->
                if (settingsLevel == 1 && item.identifier != "add_sub") {
                    showDeleteSubscriptionConfirmDialog(item)
                } else if (settingsLevel == 2 && item.identifier != "add_epg") {
                    showDeleteEpgConfirmDialog(item)
                }
            },
            { pos, isUp ->
                btnSettingsBack.requestFocus()
            }
        )
        rvSettingsMinimal.adapter = settingsMinimalAdapter

        btnSettingsBack.setOnClickListener {
            if (settingsLevel > 0) {
                val parentId = when (settingsLevel) {
                    1 -> "menu_sub"
                    2 -> "menu_epg"
                    3 -> "menu_decoder"
                    4 -> "menu_display"
                    5 -> "menu_timeout"
                    6 -> "menu_decode_mode"
                    7 -> "menu_advanced"
                    8 -> "menu_proxy_cats"
                    10 -> "menu_kernel"
                    11 -> "menu_display_settings"
                    else -> null
                }
                
                if (settingsLevel == 4) {
                    showSettingsLevel(11, parentId)
                } else if (settingsLevel == 5 || settingsLevel == 8) {
                    showSettingsLevel(7, parentId)
                } else {
                    showSettingsLevel(0, parentId)
                }
            } else {
                settingsOverlayMinimal.visibility = View.GONE
                if (menuOverlay.visibility == View.VISIBLE) {
                    rvChannels.requestFocus()
                } else {
                    playerContainer.requestFocus()
                }
            }
        }

        btnSettingsRefresh.setOnClickListener {
            if (settingsLevel == 16) {
                showSettingsLevel(16)
            } else {
                settingsOverlayMinimal.visibility = View.GONE
                rvChannels.requestFocus()
                loadPlaylist()
                android.widget.Toast.makeText(this, "正在刷新全部订阅", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        showSettingsLevel(0)
        
        btnSettingsBack.nextFocusDownId = R.id.rv_settings_minimal
        btnSettingsBack.nextFocusRightId = R.id.btn_settings_refresh
        btnSettingsBack.nextFocusLeftId = R.id.btn_settings_back
        btnSettingsBack.nextFocusUpId = R.id.btn_settings_back
        btnSettingsRefresh.nextFocusLeftId = R.id.btn_settings_back
        btnSettingsRefresh.nextFocusRightId = R.id.btn_settings_refresh
        btnSettingsRefresh.nextFocusUpId = R.id.btn_settings_refresh
        btnSettingsRefresh.nextFocusDownId = R.id.rv_settings_minimal
    }

    private fun focusChannelItem(targetIndex: Int) {
        val count = channelAdapter.itemCount
        if (count == 0) return
        val safeIndex = if (targetIndex in 0 until count) targetIndex else 0
        
        rvChannels.scrollToPosition(safeIndex)
        
        rvChannels.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rvChannels.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val layoutManager = rvChannels.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val child = layoutManager?.findViewByPosition(safeIndex)
                if (child == null || !child.requestFocus()) {
                    // 目标行还未布局出来时至少让频道栏可导航, 避免焦点滞留在播放器上
                    rvChannels.requestFocus()
                }
            }
        })
    }

    private fun focusCategoryItem(targetIndex: Int) {
        val count = categoryAdapter.itemCount
        if (count == 0) return
        val safeIndex = if (targetIndex in 0 until count) targetIndex else 0
        
        rvCategories.scrollToPosition(safeIndex)
        
        rvCategories.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rvCategories.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val layoutManager = rvCategories.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val child = layoutManager?.findViewByPosition(safeIndex)
                if (child == null || !child.requestFocus()) {
                    rvCategories.requestFocus()
                }
            }
        })
    }

    private fun forceUpdateChannelList() {
        categorySelectJob?.cancel()
        if (loadedCategoryIndex == currentMenuCategoryIndex) {
            return
        }
        val cat = categories.getOrNull(currentMenuCategoryIndex) ?: return
        val chList = channelsMap[cat] ?: emptyList()
        val startNum = getGlobalChannelNumber(currentMenuCategoryIndex, 0)
        channelAdapter.updateData(chList, startNum)
        loadedCategoryIndex = currentMenuCategoryIndex
        if (currentMenuCategoryIndex == playingCategoryIndex) {
            channelAdapter.setSelected(playingChannelIndex)
        } else {
            channelAdapter.setSelected(-1)
        }
    }

    private fun setupRecyclerViews() {
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvChannels.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        categoryAdapter = com.iptv.tvplayer.ui.CategoryAdapter(categories, { position ->
            currentMenuCategoryIndex = position
            categorySelectJob?.cancel()
            categorySelectJob = lifecycleScope.launch {
                delay(150)
                // 焦点驱动的切换要求执行时焦点仍在分类栏: 若已被列表刷新挪走(系统会把焦点
                // 退给窗口内第一个可聚焦项=分类第一项), 回滚索引/高亮, 不覆盖频道面板
                if (categoryAdapter.selectionByFocus && !rvCategories.hasFocus()) {
                    if (loadedCategoryIndex >= 0) {
                        currentMenuCategoryIndex = loadedCategoryIndex
                        categoryAdapter.setSelected(loadedCategoryIndex)
                    }
                    return@launch
                }
                val cat = categories.getOrNull(position) ?: return@launch
                val chList = channelsMap[cat] ?: emptyList()
                val startNum = getGlobalChannelNumber(position, 0)
                channelAdapter.updateData(chList, startNum)
                loadedCategoryIndex = position
                // If the playing channel is in this category, highlight it
                if (position == playingCategoryIndex) {
                    channelAdapter.setSelected(playingChannelIndex)
                } else {
                    channelAdapter.setSelected(-1)
                }
            }
            resetMenuTimer()
        }, { position, isUp ->
            if (isUp) {
                focusCategoryItem(categories.size - 1)
            } else {
                focusCategoryItem(0)
            }
        })
        rvCategories.adapter = categoryAdapter

        channelAdapter = com.iptv.tvplayer.ui.ChannelAdapter(emptyList(), { channel, position ->
            playChannel(currentMenuCategoryIndex, position, 0)
            menuOverlay.visibility = View.GONE
            playerContainer.requestFocus()
        }, { channel, hasFocus ->
            if (hasFocus) {
                menuFocusedChannel = channel
                val programsByDay = com.iptv.tvplayer.data.EpgManager.getProgramsGroupedByDay(channel.name)
                if (programsByDay.isNotEmpty() && catchupPanel.visibility == View.GONE) {
                    catchupHintTab.visibility = View.VISIBLE
                } else {
                    catchupHintTab.visibility = View.GONE
                }
            } else {
                catchupHintTab.visibility = View.GONE
            }
        }, { position, isUp ->
            val cat = categories.getOrNull(currentMenuCategoryIndex)
            val maxIndex = (channelsMap[cat]?.size ?: 1) - 1
            if (isUp) {
                focusChannelItem(maxIndex)
            } else {
                focusChannelItem(0)
            }
        }, { channel, position ->
            toggleFavorite(channel, position)
        })
        rvChannels.adapter = channelAdapter

        // Initialize Catchup Adapters
        rvCatchupDays.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        catchupDayAdapter = com.iptv.tvplayer.ui.CatchupDayAdapter(emptyList(), { day, _ ->
            if (currentCatchupDay != day) {
                currentCatchupDay = day
                val allPrograms = catchupFocusedChannel?.let { com.iptv.tvplayer.data.EpgManager.getProgramsGroupedByDay(it.name) }
                val programs = allPrograms?.get(day) ?: emptyList()
                catchupProgramAdapter.updateData(programs)
            }
        }, {
            // onBoundaryRight: focus goes to programs list
            rvCatchupPrograms.post {
                val layoutManager = rvCatchupPrograms.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val firstVisible = layoutManager?.findFirstCompletelyVisibleItemPosition() ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
                val targetPos = if (firstVisible != androidx.recyclerview.widget.RecyclerView.NO_POSITION) firstVisible else 0
                val targetView = layoutManager?.findViewByPosition(targetPos)
                if (targetView != null) {
                    targetView.requestFocus()
                } else {
                    rvCatchupPrograms.requestFocus()
                }
            }
        })
        rvCatchupDays.adapter = catchupDayAdapter

        rvCatchupPrograms.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        catchupProgramAdapter = com.iptv.tvplayer.ui.CatchupProgramAdapter(emptyList(), { program, _ ->
            playCatchupProgram(program)
        }, {
            // onBoundaryLeft: focus goes back to days list
            rvCatchupDays.requestFocus()
        })
        rvCatchupPrograms.adapter = catchupProgramAdapter
    }

    /** 按收藏表把频道对象从已加载列表里解析出来 (优先取来源分类, 否则任意分类同名频道) */
    private fun resolveFavoriteChannels(source: Map<String, List<com.iptv.tvplayer.data.Channel>>): List<com.iptv.tvplayer.data.Channel> {
        val favs = com.iptv.tvplayer.data.SettingsManager.favoriteChannels
        if (favs.isEmpty()) return emptyList()
        val lookup = source.filterKeys { it != FAVORITE_CATEGORY }
        return favs.mapNotNull { (name, srcCat) ->
            lookup[srcCat]?.find { it.name == name }
                ?: lookup.values.firstNotNullOfOrNull { list -> list.find { it.name == name } }
        }
    }

    /** 长按频道名: 未收藏则加入我的收藏, 已收藏则移除 */
    private fun toggleFavorite(channel: com.iptv.tvplayer.data.Channel, position: Int) {
        val srcCat = categories.getOrNull(currentMenuCategoryIndex) ?: ""
        val inFavorites = categories.firstOrNull() == FAVORITE_CATEGORY
        val wasViewingFavorites = inFavorites && currentMenuCategoryIndex == 0

        // 记录正在播放的收藏频道名, 列表变化后重新定位
        val playingFavName = if (inFavorites && playingCategoryIndex == 0) {
            channelsMap[FAVORITE_CATEGORY]?.getOrNull(playingChannelIndex)?.name
        } else null

        val added = com.iptv.tvplayer.data.SettingsManager.toggleFavorite(channel.name, srcCat)

        // 重建收藏分类内容
        if (inFavorites) {
            channelsMap[FAVORITE_CATEGORY] = resolveFavoriteChannels(channelsMap)
        }

        // 正在播放收藏频道时修正播放序号
        if (inFavorites && playingCategoryIndex == 0) {
            val list = channelsMap[FAVORITE_CATEGORY] ?: emptyList()
            val idx = if (playingFavName != null) list.indexOfFirst { it.name == playingFavName } else -1
            playingChannelIndex = when {
                idx >= 0 -> idx
                list.isEmpty() -> 0
                else -> playingChannelIndex.coerceAtMost(list.size - 1)
            }
        }

        if (wasViewingFavorites) {
            // 正在查看收藏列表: 刷新列表并让焦点停留在原位置附近
            val chList = channelsMap[FAVORITE_CATEGORY] ?: emptyList()
            channelAdapter.updateData(chList, getGlobalChannelNumber(0, 0))
            loadedCategoryIndex = 0
            if (playingCategoryIndex == 0) {
                channelAdapter.setSelected(playingChannelIndex)
            }
            if (chList.isEmpty()) {
                rvChannels.requestFocus()
            } else {
                focusChannelItem(position.coerceAtMost(chList.size - 1))
            }
        } else {
            // 查看其他分类: 只刷新星标显示
            channelAdapter.refreshFavorites()
        }

        android.widget.Toast.makeText(
            this,
            if (added) "★ 已收藏: ${channel.name}" else "已取消收藏: ${channel.name}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        resetMenuTimer()
    }

    private fun loadPlaylist() {
        splashImage.visibility = View.VISIBLE
        val tvLoadingList = findViewById<android.widget.TextView>(R.id.tv_loading_list)
        tvLoadingList?.visibility = View.VISIBLE
        
        val activeUrls = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls.toMutableList()
        
        // Auto-load local playlists from 摸鱼TV/list
        try {
            val localListDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "摸鱼TV/list")
            if (localListDir.exists() && localListDir.isDirectory) {
                localListDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".txt", true) || file.name.endsWith(".m3u", true) || file.name.endsWith(".m3u8", true))) {
                        activeUrls.add("file://${file.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        lifecycleScope.launch {
            try {
            if (activeUrls.isNotEmpty()) {
                val mergedCategories = mutableListOf<String>()
                val mergedChannelsMap = mutableMapOf<String, MutableList<com.iptv.tvplayer.data.Channel>>()
                
                val playlists = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    activeUrls.mapNotNull { url -> 
                        com.iptv.tvplayer.data.PlaylistParser.parse(url)
                    }
                }
                
                val categoryProxyMap = com.iptv.tvplayer.data.SettingsManager.categoryProxyMap
                val customSocksGroups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups
                
                for (playlist in playlists) {
                    for (cat in playlist.categories) {
                        if (!mergedCategories.contains(cat)) {
                            mergedCategories.add(cat)
                        }
                        if (mergedChannelsMap[cat] == null) {
                            mergedChannelsMap[cat] = mutableListOf()
                        }
                        playlist.channelsMap[cat]?.let { channels ->
                            val proxyTarget = categoryProxyMap[cat]
                            if (proxyTarget != null) {
                                val proxiedChannels = channels.map { ch ->
                                    val newUrls = ch.urls.map { url ->
                                        // 已内嵌代理参数(socks5:// / socks5= / http_proxy=)的地址不再套分类代理, 避免双重代理
                                        val hasOwnProxy = url.contains("socks5://", ignoreCase = true) || url.contains("socks5:/", ignoreCase = true) ||
                                            url.contains("socks5=", ignoreCase = true) || url.contains("http_proxy=", ignoreCase = true) ||
                                            url.contains("http_proxy://", ignoreCase = true)
                                        if (url.startsWith("http", ignoreCase = true) && !hasOwnProxy) {
                                            if (proxyTarget == "代理") {
                                                "socks5://127.0.0.1:10808/$url"
                                            } else {
                                                val group = customSocksGroups.find { it.name == proxyTarget }
                                                val ip = group?.getNextIp()
                                                if (ip != null) {
                                                    "socks5://$ip/$url"
                                                } else {
                                                    url
                                                }
                                            }
                                        } else {
                                            url
                                        }
                                    }
                                    ch.copy(urls = newUrls)
                                }
                                mergedChannelsMap[cat]!!.addAll(proxiedChannels)
                            } else {
                                mergedChannelsMap[cat]!!.addAll(channels)
                            }
                        }
                    }
                }
                
                if (mergedCategories.isNotEmpty()) {
                    // 我的收藏固定在主分类第一位 (源列表若含同名分类, 以收藏列表为准)
                    mergedCategories.remove(FAVORITE_CATEGORY)
                    mergedCategories.add(0, FAVORITE_CATEGORY)
                    mergedChannelsMap[FAVORITE_CATEGORY] = resolveFavoriteChannels(mergedChannelsMap).toMutableList()

                    categories.clear()
                    categories.addAll(mergedCategories)
                    channelsMap.clear()
                    channelsMap.putAll(mergedChannelsMap)

                    categoryAdapter.notifyDataSetChanged()
                    
                    if (categories.isNotEmpty()) {
                        val savedCat = com.iptv.tvplayer.data.SettingsManager.lastCategoryName
                        val savedCh = com.iptv.tvplayer.data.SettingsManager.lastChannelName
                        val savedUrlIdx = com.iptv.tvplayer.data.SettingsManager.lastUrlIndex
                        
                        var targetCatIndex = 0
                        var targetChIndex = 0
                        var targetUrlIndex = 0
                        
                        if (savedCat.isNotEmpty() && savedCh.isNotEmpty()) {
                            val catIdx = categories.indexOf(savedCat)
                            if (catIdx != -1) {
                                val chList = channelsMap[savedCat] ?: emptyList()
                                val chIdx = chList.indexOfFirst { it.name == savedCh }
                                if (chIdx != -1) {
                                    targetCatIndex = catIdx
                                    targetChIndex = chIdx
                                    targetUrlIndex = if (savedUrlIdx in 0 until (chList[chIdx].urls.size)) savedUrlIdx else 0
                                }
                            }
                        }
                        
                        val activeCat = categories[targetCatIndex]
                        val startNum = getGlobalChannelNumber(targetCatIndex, 0)
                        channelAdapter.updateData(channelsMap[activeCat] ?: emptyList(), startNum)
                        loadedCategoryIndex = targetCatIndex
                        categoryAdapter.setSelected(targetCatIndex)
                        channelAdapter.setSelected(targetChIndex)
                        
                        // Scroll to selection
                        rvCategories.scrollToPosition(targetCatIndex)
                        rvChannels.scrollToPosition(targetChIndex)
                        
                        playChannel(targetCatIndex, targetChIndex, targetUrlIndex)
                    }
                    tvStatus.visibility = View.GONE
                    
                    // Trigger EPG loading
                    com.iptv.tvplayer.data.EpgManager.activeChannelNames.clear()
                    channelsMap.values.forEach { list -> 
                        list.forEach { com.iptv.tvplayer.data.EpgManager.activeChannelNames.add(it.name) }
                    }
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            com.iptv.tvplayer.data.EpgManager.loadEpg(this@MainActivity)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) channelAdapter.notifyDataSetChanged()
                            }
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            android.util.Log.e("Epg", "load failed", t)
                        }
                    }
                } else {
                    // Failed to load
                    tvStatus.text = "源加载失败\n手机端：长按屏幕右侧进入设置\n电视端：长按遥控器左键进入设置"
                    tvStatus.visibility = View.VISIBLE
                    
                    // Note: If dead link, the user must deselect it manually. We shouldn't auto-remove from multi-select
                }
            } else {
                tvStatus.text = "暂未配置任何节目线路\n手机端：长按屏幕右侧进入设置\n电视端：长按遥控器左键进入设置"
                tvStatus.visibility = android.view.View.VISIBLE
            }
            splashImage.visibility = View.GONE
            tvLoadingList?.visibility = View.GONE
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                android.util.Log.e("LoadPlaylist", "load playlist failed", t)
                tvStatus.text = "源加载失败: ${t.message?.take(80) ?: t.javaClass.simpleName}"
                tvStatus.visibility = View.VISIBLE
                splashImage.visibility = View.GONE
                tvLoadingList?.visibility = View.GONE
            }
        }
    }

    private var backPressedTime = 0L
    private var dpadLeftLongPressed = false

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun playChannel(catIndex: Int, chIndex: Int, urlIndex: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(catIndex) ?: return
        val channelList = channelsMap[cat] ?: return
        val channel = channelList.getOrNull(chIndex) ?: return
        val url = channel.urls.getOrNull(urlIndex) ?: return
        
        playingCategoryIndex = catIndex
        playingChannelIndex = chIndex
        playingUrlIndex = urlIndex
        
        playingCatchupTitle = null
        playingCatchupProgram = null
        catchupProgramAdapter.setPlayingTitle(null)
        
        currentUrl = url

        // Show EPG OSD immediately
        showOsd()

        // Save last played info for memory playback
        com.iptv.tvplayer.data.SettingsManager.lastCategoryName = cat
        com.iptv.tvplayer.data.SettingsManager.lastChannelName = channel.name
        com.iptv.tvplayer.data.SettingsManager.lastUrlIndex = urlIndex

        playJob?.cancel()
        playJob = lifecycleScope.launch(
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                android.util.Log.e("PlayChannel", "play failed", t)
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "播放失败: ${t.message?.take(60) ?: t.javaClass.simpleName}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        ) {
            delay(250) // 250ms debounce to prevent spamming play requests during rapid switches
            // 内嵌代理参数格式(http://target&socks5://ip:port / ?socks5=ip:port 等)
            // 先归一化为播放器已支持的前缀格式 socks5://ip:port/http://target
            val resolvedUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ProxyManager.resolveUrl(ProxyManager.normalizeProxyUrl(url))
            }
            
            if (!isActive) return@launch
            
            val lowerUrl = resolvedUrl.lowercase()
            val isWebView = lowerUrl.startsWith("webview://") || lowerUrl.startsWith("wb://") || lowerUrl.startsWith("mb://")
            
            if (isWebView) {
                com.iptv.tvplayer.player.NativePlayerManager.release()
                
                for (i in playerContainer.childCount - 1 downTo 0) {
                    val child = playerContainer.getChildAt(i)
                    if (child is com.tencent.smtt.sdk.WebView) {
                        playerContainer.removeView(child)
                        child.destroy()
                    } else {
                        playerContainer.removeView(child)
                    }
                }
                
                val actualUrl = resolvedUrl.substringAfter("://")
                val isMobile = lowerUrl.startsWith("mb://")
                
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
                    if (actualUrl.contains("youtube.com") || actualUrl.contains("googlevideo.com") || actualUrl.contains("youtu.be")) {
                        val proxyStr = com.iptv.tvplayer.proxy.MihomoProxyManager.proxyAddress
                        if (proxyStr.isNotEmpty()) {
                            val proxyConfig = androidx.webkit.ProxyConfig.Builder()
                                .addProxyRule("socks://$proxyStr")
                                .addDirect()
                                .build()
                            androidx.webkit.ProxyController.getInstance().setProxyOverride(proxyConfig, { it.run() }, {})
                        } else {
                            androidx.webkit.ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
                        }
                    } else {
                        androidx.webkit.ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
                    }
                } else {
                    val proxyStr = com.iptv.tvplayer.proxy.MihomoProxyManager.proxyAddress
                    if (proxyStr.isNotEmpty() && (actualUrl.contains("youtube.com") || actualUrl.contains("googlevideo.com") || actualUrl.contains("youtu.be"))) {
                        runOnUiThread {
                            android.widget.Toast.makeText(this@MainActivity, "当前设备的WebView版本过低不支持网页代理，该视频可能无法播放，请升级系统WebView组件", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                
                var hasStartedPlaying = false
                var timeoutJob: kotlinx.coroutines.Job? = null

                val webView = com.tencent.smtt.sdk.WebView(this@MainActivity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    
                    try {
                        val cookieManager = com.tencent.smtt.sdk.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                    } catch (e: Exception) {}

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = 0
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    
                    if (isMobile) {
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    } else {
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    }
                    
                    addJavascriptInterface(com.iptv.tvplayer.player.WebViewBridge(
                        this,
                        onPlaying = {
                            hasStartedPlaying = true
                            timeoutJob?.cancel()
                        },
                        onError = {
                            runOnUiThread {
                                timeoutJob?.cancel()
                                android.widget.Toast.makeText(this@MainActivity, "播放出错，自动切换线路或节目", android.widget.Toast.LENGTH_SHORT).show()
                                switchNextLineOrChannel()
                            }
                        }
                    ), "WebViewBridge")
                    
                    webViewClient = object : com.tencent.smtt.sdk.WebViewClient() {
                        private fun handleIntercept(view: com.tencent.smtt.sdk.WebView?, url: String?, webRequest: com.tencent.smtt.export.external.interfaces.WebResourceRequest? = null): com.tencent.smtt.export.external.interfaces.WebResourceResponse? {
                            if (url != null && url.startsWith("http")) {
                                try {
                                    val lowerUrl = url.lowercase()
                                    
                                    // 1. Quick exclusion of static assets to avoid leaks/OOM and CPU overhead
                                    if (lowerUrl.contains(".png") || lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") || 
                                        lowerUrl.contains(".gif") || lowerUrl.contains(".webp") || lowerUrl.contains(".svg") ||
                                        lowerUrl.contains(".css") || lowerUrl.contains(".woff") || lowerUrl.contains(".ttf") ||
                                        lowerUrl.contains(".otf") || lowerUrl.contains(".eot") ||
                                        lowerUrl.contains("/ad/") || lowerUrl.contains("aegis") || lowerUrl.contains("report") ||
                                        lowerUrl.contains("analytics") || lowerUrl.contains("log")) {
                                        return null
                                    }
                                    
                                    // 2. Only intercept HTML (main frame), WASM, API/XHR, and Video Stream requests
                                    val isMainFrame = webRequest?.isForMainFrame == true
                                    val isWasmOrJs = lowerUrl.contains(".wasm") || (lowerUrl.contains(".js") && (lowerUrl.contains("wasm") || lowerUrl.contains("sapi.")))
                                    val isApi = lowerUrl.contains("api") || lowerUrl.contains("h5access") || lowerUrl.contains("/get") || lowerUrl.contains("/post")
                                    val isVideoStream = lowerUrl.contains(".m3u8") || lowerUrl.contains(".ts") || lowerUrl.contains(".m4s") || lowerUrl.contains(".mpd")
                                    
                                    if (!isMainFrame && !isWasmOrJs && !isApi && !isVideoStream) {
                                        return null
                                    }
                                    
                                    val method = webRequest?.method ?: "GET"
                                    if (method != "GET" && method != "OPTIONS" && method != "POST") {
                                        return null
                                    }
                                    
                                    val client = InterceptHttpClient.client
                                    val reqBuilder = okhttp3.Request.Builder().url(url)
                                    if (method == "OPTIONS") {
                                        reqBuilder.method("OPTIONS", null)
                                    }
                                    
                                    var hasUserAgent = false
                                    var hasCookie = false
                                    var requestHeaders = ""
                                    var requestOrigin = ""
                                    
                                    webRequest?.requestHeaders?.forEach { (key, value) ->
                                        reqBuilder.addHeader(key, value)
                                        if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                                        if (key.equals("Cookie", ignoreCase = true)) hasCookie = true
                                        if (key.equals("Access-Control-Request-Headers", ignoreCase = true)) requestHeaders = value
                                        if (key.equals("Origin", ignoreCase = true)) requestOrigin = value
                                    }
                                    
                                    if (!hasUserAgent && view != null) {
                                        reqBuilder.addHeader("User-Agent", view.settings.userAgentString)
                                    }
                                    if (!hasCookie) {
                                        val cookie = com.tencent.smtt.sdk.CookieManager.getInstance().getCookie(url)
                                        if (!cookie.isNullOrEmpty()) {
                                            reqBuilder.addHeader("Cookie", cookie)
                                        }
                                    }
                                    
                                    if (method == "POST") {
                                        return null // Do not proxy POST yet
                                    }
                                    
                                    val request = reqBuilder.build()
                                    val response = client.newCall(request).execute()
                                    
                                    val statusCode = response.code
                                    var reasonPhrase = response.message
                                    if (reasonPhrase.isNullOrEmpty()) reasonPhrase = "OK"
                                    
                                    val contentTypeHeader = response.header("Content-Type")?.lowercase() ?: ""
                                    
                                    // WASM detection via Okio peek
                                    var isWasm = false
                                    if (url.contains(".wasm")) {
                                        isWasm = true
                                    } else {
                                        try {
                                            val source = response.body?.source()
                                            if (source != null) {
                                                source.request(4)
                                                val buffer = source.buffer
                                                if (buffer.size >= 4) {
                                                    val b0 = buffer[0]
                                                    val b1 = buffer[1]
                                                    val b2 = buffer[2]
                                                    val b3 = buffer[3]
                                                    if (b0 == 0x00.toByte() && b1 == 0x61.toByte() && b2 == 0x73.toByte() && b3 == 0x6D.toByte()) {
                                                        isWasm = true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {}
                                    }
                                    
                                    val body = response.body
                                    val bytes = body?.bytes() ?: ByteArray(0)
                                    response.close() // Close response immediately to release sockets!
                                    
                                    if (contentTypeHeader.contains("text/html") && !isWasm) {
                                        val bodyStr = String(bytes, Charsets.UTF_8)
                                        val injectScript = "<script>" +
                                                "try {" +
                                                "  console.log('[WebViewJS] Hooking initialized');" +
                                                "  if (window.MediaSource) {" +
                                                "    var origIsTypeSupported = window.MediaSource.isTypeSupported;" +
                                                "    window.MediaSource.isTypeSupported = function(type) {" +
                                                "      if (typeof origIsTypeSupported !== 'function') return false;" +
                                                "      if (type && (type.indexOf('hev') !== -1 || type.indexOf('hvc') !== -1 || type.indexOf('h265') !== -1 || type.indexOf('avs') !== -1)) {" +
                                                "        return false;" +
                                                "      }" +
                                                "      return origIsTypeSupported.call(this, type);" +
                                                "    };" +
                                                "  }" +
                                                "  var origOpen = XMLHttpRequest.prototype.open;" +
                                                "  XMLHttpRequest.prototype.open = function(method, url) {" +
                                                "    console.log('[WebViewJS] XHR open: ' + method + ' ' + url);" +
                                                "    this._method = method;" +
                                                "    this._url = url;" +
                                                "    this._headers = {};" +
                                                "    return origOpen.apply(this, arguments);" +
                                                "  };" +
                                                "  var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;" +
                                                "  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {" +
                                                "    if (!this._headers) this._headers = {};" +
                                                "    this._headers[name] = value;" +
                                                "    return origSetHeader.apply(this, arguments);" +
                                                "  };" +
                                                "  var origSend = XMLHttpRequest.prototype.send;" +
                                                "  XMLHttpRequest.prototype.send = function(body) {" +
                                                "    var self = this;" +
                                                "    var urlStr = (self._url || '').toString();" +
                                                "    var methodStr = (self._method || 'GET').toUpperCase();" +
                                                "    var isPost = methodStr === 'POST';" +
                                                "    var isApi = urlStr.indexOf('api') !== -1 || urlStr.indexOf('h5access') !== -1;" +
                                                "    console.log('[WebViewJS] XHR send, url: ' + urlStr + ', method: ' + methodStr);" +
                                                "    if (isPost && isApi) {" +
                                                "      console.log('[WebViewJS] Intercepted XHR POST API request: ' + urlStr);" +
                                                "      if (window.WebViewBridge && window.WebViewBridge.proxyPost) {" +
                                                "        var reqId = 'req_' + Math.random().toString(36).substring(2);" +
                                                "        window[reqId] = function(statusCode, responseText, responseHeadersJson) {" +
                                                "          console.log('[WebViewJS] Received proxyPost callback, status: ' + statusCode);" +
                                                "          Object.defineProperty(self, 'readyState', { value: 4, writable: true });" +
                                                "          Object.defineProperty(self, 'status', { value: statusCode, writable: true });" +
                                                "          Object.defineProperty(self, 'responseText', { value: responseText, writable: true });" +
                                                "          var parsedResponse = responseText;" +
                                                "          if (self.responseType === 'json') {" +
                                                "            try {" +
                                                "              parsedResponse = JSON.parse(responseText);" +
                                                "            } catch(e) {" +
                                                "              console.log('[WebViewJS] ERROR parsing JSON response: ' + e.message);" +
                                                "            }" +
                                                "          }" +
                                                "          Object.defineProperty(self, 'response', { value: parsedResponse, writable: true });" +
                                                "          var respHeaders = JSON.parse(responseHeadersJson || '{}');" +
                                                "          self.getAllResponseHeaders = function() {" +
                                                "            var lines = [];" +
                                                "            for (var k in respHeaders) {" +
                                                "              lines.push(k + ': ' + respHeaders[k]);" +
                                                "            }" +
                                                "            return lines.join('\\r\\n');" +
                                                "          };" +
                                                "          self.getResponseHeader = function(name) {" +
                                                "            return respHeaders[name.toLowerCase()] || null;" +
                                                "          };" +
                                                "          if (self.onreadystatechange) self.onreadystatechange();" +
                                                "          if (self.onload) self.onload();" +
                                                "          delete window[reqId];" +
                                                "        };" +
                                                "        var headers = self._headers || {};" +
                                                "        console.log('[WebViewJS] Calling WebViewBridge.proxyPost');" +
                                                "        window.WebViewBridge.proxyPost(urlStr, JSON.stringify(headers), body || '', reqId);" +
                                                "        return;" +
                                                "      } else {" +
                                                "        console.log('[WebViewJS] WARNING: WebViewBridge.proxyPost is NOT defined!');" +
                                                "      }" +
                                                "    }" +
                                                "    return origSend.apply(this, arguments);" +
                                                "  };" +
                                                "  var origFetch = window.fetch;" +
                                                "  if (origFetch) {" +
                                                "    window.fetch = function(input, init) {" +
                                                "      var url = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';" +
                                                "      var urlStr = url.toString();" +
                                                "      init = init || {};" +
                                                "      var method = (init.method || 'GET').toUpperCase();" +
                                                "      var isPost = method === 'POST';" +
                                                "      var isApi = urlStr.indexOf('api') !== -1 || urlStr.indexOf('h5access') !== -1;" +
                                                "      if (isPost && isApi && window.WebViewBridge && window.WebViewBridge.proxyPost) {" +
                                                "        console.log('[WebViewJS] Intercepted Fetch POST API request: ' + urlStr);" +
                                                "        return new Promise(function(resolve, reject) {" +
                                                "          var reqId = 'req_' + Math.random().toString(36).substring(2);" +
                                                "          window[reqId] = function(statusCode, responseText, responseHeadersJson) {" +
                                                "            var headers = JSON.parse(responseHeadersJson || '{}');" +
                                                "            var resp = new Response(responseText, {" +
                                                "              status: statusCode," +
                                                "              statusText: 'OK'," +
                                                "              headers: new Headers(headers)" +
                                                "            });" +
                                                "            resolve(resp);" +
                                                "            delete window[reqId];" +
                                                "          };" +
                                                "          var body = init.body || '';" +
                                                "          if (typeof body !== 'string') {" +
                                                "            try { body = JSON.stringify(body); } catch(e) { body = ''; }" +
                                                "          }" +
                                                "          var headers = init.headers || {};" +
                                                "          var headersMap = {};" +
                                                "          if (headers instanceof Headers) {" +
                                                "            headers.forEach(function(v, k) { headersMap[k] = v; });" +
                                                "          } else if (Array.isArray(headers)) {" +
                                                "            headers.forEach(function(h) { headersMap[h[0]] = h[1]; });" +
                                                "          } else {" +
                                                "            headersMap = headers;" +
                                                "          }" +
                                                "          window.WebViewBridge.proxyPost(urlStr, JSON.stringify(headersMap), body, reqId);" +
                                                "        });" +
                                                "      }" +
                                                "      init.credentials = 'same-origin';" +
                                                "      return origFetch.call(this, input, init);" +
                                                "    };" +
                                                "  }" +
                                                "} catch(e) {" +
                                                "  console.log('[WebViewJS] ERROR in Hook: ' + e.message);" +
                                                "};" +
                                                "</script>"
                                        
                                        var modifiedHtml = bodyStr
                                        if (bodyStr.contains("<head>", ignoreCase = true)) {
                                            modifiedHtml = bodyStr.replace(Regex("(?i)<head>"), "<head>$injectScript")
                                        } else if (bodyStr.contains("<body>", ignoreCase = true)) {
                                            modifiedHtml = bodyStr.replace(Regex("(?i)<body>"), "<body>$injectScript")
                                        }
                                        
                                        val inputStream = java.io.ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
                                        val webResponse = com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                                            "text/html",
                                            "UTF-8",
                                            inputStream
                                        )
                                        webResponse.setStatusCodeAndReasonPhrase(statusCode, reasonPhrase)
                                        val headers = HashMap<String, String>()
                                        response.headers.forEach { (name, value) ->
                                            if (!name.equals("Content-Encoding", true) && !name.equals("Transfer-Encoding", true) && !name.equals("Content-Length", true)) {
                                                headers[name] = value
                                            }
                                        }
                                        webResponse.responseHeaders = headers
                                        return webResponse
                                        
                                    } else {
                                        var mimeType = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "application/octet-stream"
                                        if (isWasm) {
                                            mimeType = "application/wasm"
                                        } else if (url.contains(".js") || url.contains("jsonp")) {
                                            mimeType = "application/javascript"
                                        }
                                        
                                        val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "UTF-8" else null
                                        val inputStream = java.io.ByteArrayInputStream(bytes)
                                        val webResponse = com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                                            mimeType,
                                            encoding,
                                            inputStream
                                        )
                                        webResponse.setStatusCodeAndReasonPhrase(statusCode, reasonPhrase)
                                        
                                        val headers = HashMap<String, String>()
                                        var originalAcao: String? = null
                                        response.headers.forEach { (name, value) ->
                                            if (!name.equals("Transfer-Encoding", true) && !name.equals("Content-Encoding", true)
                                                && !name.equals("Access-Control-Allow-Origin", true)
                                                && !name.equals("Access-Control-Allow-Headers", true)
                                                && !name.equals("Access-Control-Allow-Methods", true)
                                                && !name.equals("Access-Control-Allow-Credentials", true)) {
                                                headers[name] = value
                                            }
                                            if (name.equals("Access-Control-Allow-Origin", true)) originalAcao = value
                                        }
                                        
                                        var originToSet = requestOrigin
                                        if (originToSet.isEmpty() || originToSet == "*") {
                                            originToSet = "https://yangshipin.cn"
                                        }
                                        headers["Access-Control-Allow-Origin"] = originToSet
                                        
                                        if (requestHeaders.isNotEmpty()) {
                                            headers["Access-Control-Allow-Headers"] = requestHeaders
                                        } else {
                                            headers["Access-Control-Allow-Headers"] = "yspappid, Content-Type, Authorization, X-Requested-With, *"
                                        }
                                        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
                                        headers["Access-Control-Allow-Credentials"] = "true"
                                        
                                        webResponse.responseHeaders = headers
                                        return webResponse
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("WebViewPlayer", "Error proxying: ${e.message} | URL: $url")
                                }
                            }
                            return null
                        }

override fun shouldInterceptRequest(view: com.tencent.smtt.sdk.WebView?, url: String?): com.tencent.smtt.export.external.interfaces.WebResourceResponse? {
                                return handleIntercept(view, url) ?: super.shouldInterceptRequest(view, url)
                            }

                        override fun shouldInterceptRequest(view: com.tencent.smtt.sdk.WebView?, request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?): com.tencent.smtt.export.external.interfaces.WebResourceResponse? {
                            val url = request?.url?.toString()
                            return handleIntercept(view, url, request) ?: super.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: com.tencent.smtt.sdk.WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            hasStartedPlaying = true
                            timeoutJob?.cancel()
                            try {
                                val scaleType = when (com.iptv.tvplayer.data.SettingsManager.aspectRatio) {
                                    "填充" -> 3
                                    "裁剪" -> 5
                                    "16:9" -> 3
                                    else -> 0
                                }
                                val isX5 = com.iptv.tvplayer.data.SettingsManager.enableTbsX5
                                val rawJs = assets.open("webview_inject.js").bufferedReader().use { it.readText() }
                                val uri = android.net.Uri.parse(view?.url ?: actualUrl)
                                val channelId = uri?.getQueryParameter("channelId") ?: ""
                                val js = rawJs
                                    .replace("__SCALE_TYPE__", scaleType.toString())
                                    .replace("__IS_X5_CORE__", isX5.toString())
                                    .replace("__CHANNEL_ID__", channelId)
                                view?.evaluateJavascript(js, null)
                                runOnUiThread {
                                    if (bottomOsdBar.visibility == View.VISIBLE) {
                                        updateOsdContent()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onReceivedError(
                            view: com.tencent.smtt.sdk.WebView?,
                            request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?,
                            error: com.tencent.smtt.export.external.interfaces.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                view?.postDelayed({ view.reload() }, 3000)
                            }
                        }

                        override fun onReceivedError(
                            view: com.tencent.smtt.sdk.WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            view?.postDelayed({ view.reload() }, 3000)
                        }

                        override fun onReceivedSslError(
                            view: com.tencent.smtt.sdk.WebView?,
                            handler: com.tencent.smtt.export.external.interfaces.SslErrorHandler?,
                            error: com.tencent.smtt.export.external.interfaces.SslError?
                        ) {
                            handler?.proceed() // Proceed on SSL errors (common on older Android devices with expired root certs)
                        }
                    }
                    
                    webChromeClient = object : com.tencent.smtt.sdk.WebChromeClient() {
                        private var customView: View? = null
                        private var customViewCallback: com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback? = null
 
                        override fun onShowCustomView(view: View?, callback: com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback?) {
                            if (customView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            playerContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                            this@apply.visibility = View.GONE
                        }
 
                        override fun onHideCustomView() {
                            if (customView == null) return
                            playerContainer.removeView(customView)
                            customView = null
                            this@apply.visibility = View.VISIBLE
                            customViewCallback?.onCustomViewHidden()
                        }

                        override fun onConsoleMessage(consoleMessage: com.tencent.smtt.export.external.interfaces.ConsoleMessage?): Boolean {
                            android.util.Log.d("WebViewJS", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                            return true
                        }
                    }
                }
                
                if (!isActive) {
                    webView.destroy()
                    return@launch
                }
                
                val timeoutSeconds = 30L // WebView always uses 30 seconds to allow for page parsing and buffering
                timeoutJob = launch {
                    delay(timeoutSeconds * 1000L)
                    if (!hasStartedPlaying) {
                        android.widget.Toast.makeText(this@MainActivity, "网页加载超时，自动切换线路或节目", android.widget.Toast.LENGTH_SHORT).show()
                        switchNextLineOrChannel()
                    }
                }
                
                playerContainer.addView(webView)
                webView.requestFocus()
                webView.loadUrl(actualUrl)
            } else {
                for (i in playerContainer.childCount - 1 downTo 0) {
                    val child = playerContainer.getChildAt(i)
                    if (child is com.tencent.smtt.sdk.WebView) {
                        playerContainer.removeView(child)
                        child.destroy()
                    }
                }
                
                val isDrm = !channel.drmType.isNullOrBlank() && !channel.drmKey.isNullOrBlank()
                var decoderType = if (isDrm) "EXO" else com.iptv.tvplayer.data.SettingsManager.decoderTypeState.value
                val lowerUrl = resolvedUrl.lowercase()
                if (decoderType == "EXO" && (lowerUrl.contains("avs3") || lowerUrl.contains("av3a"))) {
                    decoderType = "MPV"
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "检测到 AVS3 流，已自动切换至 MPV 内核播放", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                if (decoderType == "EXO" && (lowerUrl.startsWith("rtsp://") ||
                        Regex("^(sk|socks5|proxy)://[^/\\uFF06]+[/&\\uFF06]rtsp://", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl))) {
                    decoderType = "MPV"
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "RTSP 流已自动切换至 MPV 内核播放", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                com.iptv.tvplayer.player.NativePlayerManager.onAutoSwitchRequested = {
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "连接超时，自动切换线路或节目", android.widget.Toast.LENGTH_SHORT).show()
                        switchNextLineOrChannel()
                    }
                }
                com.iptv.tvplayer.player.NativePlayerManager.onPlayerBuffering = {
                    // Removed the buffering spinner as per user request to keep screen clean
                }
                com.iptv.tvplayer.player.NativePlayerManager.onPlayerReady = {
                    runOnUiThread {
                        val currentView = com.iptv.tvplayer.player.NativePlayerManager.getCurrentView()
                        for (i in playerContainer.childCount - 1 downTo 0) {
                            val child = playerContainer.getChildAt(i)
                            if (child != currentView) {
                                playerContainer.removeView(child)
                            }
                        }
                        if (bottomOsdBar.visibility == View.VISIBLE) {
                            updateOsdContent()
                        }
                    }
                }
                val playerView = if (decoderType == "EXO") {
                    com.iptv.tvplayer.player.NativePlayerManager.createExoPlayer(this@MainActivity, resolvedUrl, channel.drmType, channel.drmKey)
                } else {
                    com.iptv.tvplayer.player.NativePlayerManager.createMPVPlayer(this@MainActivity, resolvedUrl)
                }
                
                if (!isActive) {
                    com.iptv.tvplayer.player.NativePlayerManager.release()
                    return@launch
                }
                
                if (playerView.parent == null) {
                    playerContainer.addView(playerView)
                }
            }
        }
    }

    private fun resetMenuTimer() {
        handler.removeCallbacks(hideMenuRunnable)
        handler.postDelayed(hideMenuRunnable, 8000)
    }

    private fun hideMenuAndHints() {
        menuOverlay.visibility = View.GONE
        catchupHintTab.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetMenuTimer()
        val keyCode = event.keyCode
        
        if (event.action == KeyEvent.ACTION_DOWN) {
            var handled = false
            if (KeyUtil.isDigitKey(event)) {
                if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                    return super.dispatchKeyEvent(event)
                }
                if (menuOverlay.visibility == View.VISIBLE) {
                    hideMenuAndHints()
                }
                val digit = if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                    (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
                } else {
                    (keyCode - KeyEvent.KEYCODE_0).toString()
                }
                appendNumberInput(digit)
                handled = true
            } else if (KeyUtil.isUpKey(event) && menuOverlay.visibility == View.GONE && settingsOverlayMinimal.visibility == View.GONE) {
                // CHANNEL_UP / PAGE_UP: switch channel without showing menu
                switchChannel(-1)
                handled = true
            } else if (KeyUtil.isDownKey(event) && menuOverlay.visibility == View.GONE && settingsOverlayMinimal.visibility == View.GONE) {
                // CHANNEL_DOWN / PAGE_DOWN: switch channel without showing menu
                switchChannel(1)
                handled = true
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                        if (settingsOverlayMinimal.visibility == View.GONE) {
                            hideMenuAndHints()
                            settingsOverlayMinimal.visibility = View.VISIBLE
                            rvSettingsMinimal.requestFocus()
                            setupSettingsMenu() // Refresh data
                        } else {
                            settingsOverlayMinimal.visibility = View.GONE
                            if (menuOverlay.visibility == View.VISIBLE) {
                                rvChannels.requestFocus()
                            } else {
                                playerContainer.requestFocus()
                            }
                        }
                        handled = true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (tvNumberSelectOverlay.visibility == View.VISIBLE) {
                            confirmNumberSelection()
                            handled = true
                        } else if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            // Let super handle it
                        } else if (menuOverlay.visibility == View.GONE) {
                            if (bottomOsdBar.visibility == View.GONE) {
                                showOsd()
                            } else {
                                bottomOsdBar.visibility = View.GONE
                                menuOverlay.visibility = View.VISIBLE
                                // Focus on current category and channel
                                currentMenuCategoryIndex = playingCategoryIndex
                                categoryAdapter.setSelected(playingCategoryIndex)
                                rvCategories.scrollToPosition(playingCategoryIndex)
                                
                                val cat = categories.getOrNull(playingCategoryIndex)
                                if (cat != null) {
                                    val chList = channelsMap[cat] ?: emptyList()
                                    val startNum = getGlobalChannelNumber(playingCategoryIndex, 0)
                                    channelAdapter.updateData(chList, startNum)
                                    loadedCategoryIndex = playingCategoryIndex
                                    channelAdapter.setSelected(playingChannelIndex)
                                    rvChannels.scrollToPosition(playingChannelIndex)
                                }
                                focusChannelItem(playingChannelIndex)
                            }
                            handled = true
                        } else {
                            // Menu is visible, manually trigger click on focused item
                            val view = window.currentFocus
                            if (view != null && (rvChannels.hasFocus() || rvCategories.hasFocus())) {
                                view.performClick()
                                handled = true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        if (tvNumberSelectOverlay.visibility == View.VISIBLE) {
                            handler.removeCallbacks(runConfirmNumberSelection)
                            tvNumberSelectOverlay.visibility = View.GONE
                            numberInputBuffer = ""
                            handled = true
                        } else if (catchupPanel.visibility == View.VISIBLE) {
                            catchupPanel.visibility = View.GONE
                            if (menuOverlay.visibility == View.VISIBLE) {
                                rvChannels.requestFocus()
                            } else {
                                playerContainer.requestFocus()
                            }
                            handled = true
                        } else if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            if (settingsLevel == 12) {
                                showSettingsLevel(8, "catproxy_$currentCategoryProxyEdit")
                            } else if (settingsLevel == 14) {
                                showSettingsLevel(13, "socks_group_$currentSocksGroupEdit")
                            } else if (settingsLevel == 8 || settingsLevel == 13 || settingsLevel == 11 || settingsLevel == 15) {
                                val parentId = when (settingsLevel) {
                                    8 -> "menu_proxy_cats"
                                    13 -> "menu_socks5_groups"
                                    15 -> "menu_proxy_sub"
                                    else -> "menu_display"
                                }
                                showSettingsLevel(7, parentId)
                            } else if (settingsLevel == 16) {
                                showSettingsLevel(15, "menu_proxy_nodes")
                            } else if (settingsLevel > 0) {
                                val parentId = when (settingsLevel) {
                                    1 -> "menu_sub"
                                    2 -> "menu_epg"
                                    3 -> "menu_decoder"
                                    4 -> "menu_display"
                                    5 -> "menu_timeout"
                                    6 -> "menu_decode_mode"
                                    7 -> "menu_advanced"
                                    else -> null
                                }
                                showSettingsLevel(0, parentId)
                            } else {
                                settingsOverlayMinimal.visibility = View.GONE
                                if (menuOverlay.visibility == View.VISIBLE) {
                                    rvChannels.requestFocus()
                                } else {
                                    playerContainer.requestFocus()
                                }
                            }
                            handled = true
                        } else if (menuOverlay.visibility == View.VISIBLE) {
                            menuOverlay.visibility = View.GONE
                            handled = true
                        } else if (bottomOsdBar.visibility == View.VISIBLE) {
                            bottomOsdBar.visibility = View.GONE
                            handled = true
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - backPressedTime > 2000) {
                                android.widget.Toast.makeText(this, "再按一次退出应用", android.widget.Toast.LENGTH_SHORT).show()
                                backPressedTime = currentTime
                                handled = true
                            } else {
                                finish()
                                handled = true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            // Let settings overlay handle it
                        } else if (menuOverlay.visibility == View.VISIBLE) {
                            if (catchupPanel.visibility == View.VISIBLE) {
                                if (rvCatchupDays.hasFocus() || rvCatchupPrograms.hasFocus()) {
                                    catchupPanel.visibility = View.GONE
                                    catchupHintTab.visibility = View.VISIBLE
                                    rvChannels.requestFocus()
                                    handled = true
                                }
                            } else if (rvChannels.hasFocus()) {
                                focusCategoryItem(currentMenuCategoryIndex)
                                handled = true
                            } else if (rvCategories.hasFocus()) {
                                // Keep focus inside category list, do not jump left
                                handled = true
                            }
                        } else {
                            if (event.repeatCount == 10) {
                                hideMenuAndHints()
                                settingsOverlayMinimal.visibility = View.VISIBLE
                                rvSettingsMinimal.requestFocus()
                                setupSettingsMenu()
                                dpadLeftLongPressed = true
                            } else if (event.repeatCount == 0) {
                                dpadLeftLongPressed = false
                            }
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            // Let settings overlay handle it
                        } else if (menuOverlay.visibility == View.VISIBLE) {
                            if (rvCategories.hasFocus() || btnSettingsTop.hasFocus()) {
                                forceUpdateChannelList()
                                val targetIndex = if (currentMenuCategoryIndex == playingCategoryIndex) playingChannelIndex else playingChannelIndex
                                focusChannelItem(targetIndex)
                                handled = true
                            } else if (rvChannels.hasFocus() || catchupHintTab.hasFocus()) {
                                val channel = menuFocusedChannel
                                if (channel != null) {
                                    showCatchupPanelForChannel(channel)
                                } else {
                                    switchUrl(1)
                                }
                                handled = true
                            }
                        } else {
                            switchUrl(1)
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            // Let settings handle it
                        } else if (menuOverlay.visibility == View.GONE) {
                            switchChannel(-1)
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                            // Let settings handle it
                        } else if (menuOverlay.visibility == View.GONE) {
                            switchChannel(1)
                            handled = true
                        }
                    }
                }
            }
            if (handled) {
                activityConsumedKeys.add(keyCode)
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (activityConsumedKeys.contains(keyCode)) {
                activityConsumedKeys.remove(keyCode)
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !dpadLeftLongPressed && menuOverlay.visibility == View.GONE && settingsOverlayMinimal.visibility == View.GONE) {
                    switchUrl(-1)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchChannel(offset: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: return
        val channelList = channelsMap[cat] ?: return
        if (channelList.isEmpty()) return
        
        var newChIndex = playingChannelIndex + offset
        var newCatIndex = playingCategoryIndex
        
        if (newChIndex < 0) {
            // Previous category
            newCatIndex = if (playingCategoryIndex > 0) playingCategoryIndex - 1 else categories.size - 1
            val newCat = categories[newCatIndex]
            newChIndex = (channelsMap[newCat]?.size ?: 1) - 1
        } else if (newChIndex >= channelList.size) {
            // Next category
            newCatIndex = if (playingCategoryIndex < categories.size - 1) playingCategoryIndex + 1 else 0
            newChIndex = 0
        }
        
        // Show toast
        val chName = channelsMap[categories[newCatIndex]]?.getOrNull(newChIndex)?.name ?: ""
        android.widget.Toast.makeText(this, "切至: $chName", android.widget.Toast.LENGTH_SHORT).show()
        
        playChannel(newCatIndex, newChIndex, 0)
    }

    private fun switchUrl(offset: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: return
        val channel = channelsMap[cat]?.getOrNull(playingChannelIndex) ?: return
        val urlCount = channel.urls.size
        if (urlCount <= 1) {
            android.widget.Toast.makeText(this, "当前频道只有一条线路", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        var newUrlIndex = playingUrlIndex + offset
        if (newUrlIndex < 0) newUrlIndex = urlCount - 1
        if (newUrlIndex >= urlCount) newUrlIndex = 0
        
        android.widget.Toast.makeText(this, "切换线路: ${newUrlIndex + 1}/$urlCount", android.widget.Toast.LENGTH_SHORT).show()
        playChannel(playingCategoryIndex, playingChannelIndex, newUrlIndex)
    }

    private fun switchNextLineOrChannel() {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: return
        val channel = channelsMap[cat]?.getOrNull(playingChannelIndex) ?: return
        val urlCount = channel.urls.size
        if (urlCount <= 1 || playingUrlIndex >= urlCount - 1) {
            switchChannel(1)
        } else {
            switchUrl(1)
        }
    }

    fun showQrDialog(url: String, title: String) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val ivQr = dialogView.findViewById<android.widget.ImageView>(R.id.iv_qr)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_qr_title)
        val tvUrl = dialogView.findViewById<android.widget.TextView>(R.id.tv_qr_url)
        
        tvTitle.text = title
        tvUrl.text = url
        
        try {
            val size = 500
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            ivQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .show()
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val currentCat = categories.getOrNull(playingCategoryIndex) ?: return false
                val chList = channelsMap[currentCat] ?: return false
                val currentCh = chList.getOrNull(playingChannelIndex) ?: return false
                showCatchupPanelForChannel(currentCh)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                    settingsOverlayMinimal.visibility = View.GONE
                    rvChannels.requestFocus()
                    return true
                }
                if (menuOverlay.visibility == View.VISIBLE) {
                    menuOverlay.visibility = View.GONE
                    return true
                }
                if (bottomOsdBar.visibility == View.VISIBLE) {
                    bottomOsdBar.visibility = View.GONE
                    menuOverlay.visibility = View.VISIBLE
                    currentMenuCategoryIndex = playingCategoryIndex
                    categoryAdapter.setSelected(playingCategoryIndex)
                    rvCategories.scrollToPosition(playingCategoryIndex)
                    
                    val cat = categories.getOrNull(playingCategoryIndex)
                    if (cat != null) {
                        val chList = channelsMap[cat] ?: emptyList()
                        val startNum = getGlobalChannelNumber(playingCategoryIndex, 0)
                        channelAdapter.updateData(chList, startNum)
                        channelAdapter.setSelected(playingChannelIndex)
                        rvChannels.scrollToPosition(playingChannelIndex)
                        rvChannels.post {
                            val v = rvChannels.layoutManager?.findViewByPosition(playingChannelIndex)
                            v?.requestFocus() ?: rvChannels.requestFocus()
                        }
                    }
                    return true
                } else {
                    showOsd()
                    return true
                }
            }

            override fun onLongPress(e: MotionEvent) {
                val screenWidth = resources.displayMetrics.widthPixels
                // Only trigger when long pressing on the right side (right 40%) of the screen
                if (e.x > screenWidth * 0.6f) {
                    if (settingsOverlayMinimal.visibility == View.GONE) {
                        menuOverlay.visibility = View.GONE
                        settingsOverlayMinimal.visibility = View.VISIBLE
                        showSettingsLevel(0)
                        rvSettingsMinimal.requestFocus()
                    }
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                val threshold = 100
                val velocityThreshold = 100
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > threshold && Math.abs(velocityX) > velocityThreshold) {
                        if (diffX > 0) {
                            switchUrl(1)
                        } else {
                            switchUrl(-1)
                        }
                        return true
                    }
                } else {
                    if (Math.abs(diffY) > threshold && Math.abs(velocityY) > velocityThreshold) {
                        if (diffY > 0) {
                            switchChannel(-1) // Swipe down -> previous channel
                        } else {
                            switchChannel(1) // Swipe up -> next channel
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun isTouchInsideView(view: View?, event: MotionEvent): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        val l = IntArray(2)
        view.getLocationOnScreen(l)
        val x = event.rawX
        val y = event.rawY
        return x >= l[0] && x <= l[0] + view.width && y >= l[1] && y <= l[1] + view.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val touchInMenu = isTouchInsideView(menuOverlay, ev)
        val touchInSettings = isTouchInsideView(settingsOverlayMinimal, ev)
        
        if (touchInMenu || touchInSettings) {
            resetMenuTimer()
            return super.dispatchTouchEvent(ev)
        }
        
        if (gestureDetector.onTouchEvent(ev)) {
            resetMenuTimer()
            return true
        }
        
        return super.dispatchTouchEvent(ev)
    }
    
    fun showInputDialog(type: String) {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle(type)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val px40 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
            val px20 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            setPadding(px40, px20, px40, px20)
        }

        val et1 = android.widget.EditText(context)

        when (type) {
            "代理订阅" -> {
                et1.hint = "请输入clash订阅链接或单节点链接"
                et1.setText(com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl)
                container.addView(et1)
            }
            "UserAgent" -> {
                et1.hint = "User-Agent"
                et1.setText(com.iptv.tvplayer.data.SettingsManager.userAgent)
                container.addView(et1)
            }
            "SOCKS5组名" -> {
                et1.hint = "例如: 第一组..."
                container.addView(et1)
            }
            "SOCKS5_IP列表" -> {
                et1.hint = "格式: IP1:Port1/IP2:Port2"
                val group = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.find { it.name == currentSocksGroupEdit }
                if (group != null) {
                    et1.setText(group.ips.joinToString("/"))
                }
                container.addView(et1)
            }
            "Headers" -> {
                et1.hint = "{\"User-Agent\":\"Player\"}"
                et1.setText(com.iptv.tvplayer.data.SettingsManager.headers)
                container.addView(et1)
            }
        }
        
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
        }
        val btnLanUpload = android.widget.Button(context).apply {
            text = "扫码/局域网推流"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnLanUpload)
        container.addView(actionLayout)

        builder.setView(container)

        val alertDialog = builder.create()

        btnLanUpload.setOnClickListener {
            alertDialog.dismiss()
            checkAndCreateFolders(forcePrompt = true)
            val ip = getLocalIpAddress()
            showQrDialog("http://$ip:8080", "扫码局域网推流高级配置")
        }

        alertDialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "保存") { dialog, _ ->
            when (type) {
                "代理订阅" -> {
                    com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl = et1.text.toString().trim()
                    com.iptv.tvplayer.proxy.MihomoProxyManager.restart(context)
                    showSettingsLevel(7)
                }
                "UserAgent" -> {
                    com.iptv.tvplayer.data.SettingsManager.userAgent = et1.text.toString().trim()
                    showSettingsLevel(7)
                }
                "SOCKS5组名" -> {
                    val name = et1.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val groups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.toMutableList()
                        if (groups.none { it.name == name }) {
                            groups.add(com.iptv.tvplayer.data.SocksGroup(name, emptyList()))
                            com.iptv.tvplayer.data.SettingsManager.customSocksGroups = groups
                        }
                    }
                    showSettingsLevel(13)
                }
                "SOCKS5_IP列表" -> {
                    val ips = et1.text.toString().split(Regex("[\n/,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                    android.widget.Toast.makeText(this@MainActivity, "正在后台测速验证代理节点，请稍候...", android.widget.Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val validIps = com.iptv.tvplayer.utils.ProxyUtils.filterValidSocks5Ips(ips)
                        if (validIps.isNotEmpty()) {
                            val groups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.toMutableList()
                            val idx = groups.indexOfFirst { it.name == currentSocksGroupEdit }
                            if (idx != -1) {
                                groups[idx] = groups[idx].copy(ips = validIps)
                                com.iptv.tvplayer.data.SettingsManager.customSocksGroups = groups
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(this@MainActivity, "代理组更新成功，有效节点: ${validIps.size}个", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(this@MainActivity, "代理组更新失败，所有节点均无法连接", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    showSettingsLevel(14)
                }
                "Headers" -> {
                    com.iptv.tvplayer.data.SettingsManager.headers = et1.text.toString().trim()
                    showSettingsLevel(7)
                }
            }
            android.widget.Toast.makeText(context, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        alertDialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "取消") { dialog, _ -> dialog.cancel() }
        alertDialog.show()
    }
    private fun showX5DownloadDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("下载并安装腾讯X5内核")
        builder.setMessage("需要下载约30MB的内核文件，安装完成后需重启APP。是否继续？")
        
        builder.setPositiveButton("下载") { dialog, _ ->
            dialog.dismiss()
            val progressDialog = android.app.ProgressDialog(this)
            progressDialog.setTitle("正在下载X5内核")
            progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.max = 100
            progressDialog.setCancelable(false)
            progressDialog.show()
            
            TvPlayerApplication.downloadAndInstallX5(this, onProgress = { progress ->
                handler.post {
                    if (progress == 100) {
                        progressDialog.setTitle("正在安装X5内核，请稍候..")
                        progressDialog.isIndeterminate = true
                    } else {
                        progressDialog.progress = progress
                    }
                }
            }, onFinish = { success ->
                handler.post {
                    progressDialog.dismiss()
                    if (success) {
                        com.iptv.tvplayer.data.SettingsManager.enableTbsX5 = true
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("安装成功")
                            .setMessage("X5内核已成功安装，请重启APP以使内核生效")
                            .setPositiveButton("立即重启") { _, _ ->
                                val intent = packageManager.getLaunchIntentForPackage(packageName)
                                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(intent)
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, "内核下载或安装失败，请稍后重试", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        builder.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
        val dialog = builder.create()
        dialog.show()
        
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    fun showAddSubscriptionDialog() {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("添加订阅")

        val scrollView = android.widget.ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val px40 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
            val px20 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            setPadding(px40, px20, px40, px20)
        }
        scrollView.addView(container)

        // Input Name
        val etName = android.widget.EditText(context).apply {
            hint = "订阅源名 (如：我的自建)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etName)

        // Input URL
        val etUrl = android.widget.EditText(context).apply {
                        hint = "订阅源网址 (http://... / https://...)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etUrl)

        // Divider or spacing
        val space = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                20
            )
        }
        container.addView(space)

        // Layout for action buttons (File picker & LAN upload)
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }

        // Button: Select Local File
        val btnSelectFile = android.widget.Button(context).apply {
            text = "导入本地列表文件"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnSelectFile)

        // Button: LAN Upload (QR Code)
        val btnLanUpload = android.widget.Button(context).apply {
            text = "扫码/局域网推流"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnLanUpload)

        container.addView(actionLayout)
        builder.setView(scrollView)

        builder.setPositiveButton("确定") { _, _ ->
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val newSub = com.iptv.tvplayer.data.Subscription(name, url)
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                currentSubs.add(newSub)
                com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                
                val currentActive = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls.toMutableSet()
                currentActive.add(url)
                com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls = currentActive
                
                android.widget.Toast.makeText(context, "添加并激活：$name", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 1) {
                    showSettingsLevel(1)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(context, "名称或URL不能为空", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }

        val alertDialog = builder.create()

        btnSelectFile.setOnClickListener {
            alertDialog.dismiss()
            showInternalFilePicker()
        }

        btnLanUpload.setOnClickListener {
            alertDialog.dismiss()
            checkAndCreateFolders(forcePrompt = true)
            val ip = getLocalIpAddress()
            showQrDialog("http://$ip:8080", "扫码添加/推送订阅源")
        }

        alertDialog.show()
    }

    fun showAddEpgDialog() {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("添加 EPG")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // Input Name
        val etName = android.widget.EditText(context).apply {
            hint = "EPG源名 (如: 我的EPG)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etName)

        // Input URL
        val etUrl = android.widget.EditText(context).apply {
            hint = "EPG链接 (http://.../epg.xml.gz)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etUrl)

        // Divider or spacing
        val space = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                20
            )
        }
        container.addView(space)

        // Layout for action buttons (LAN upload)
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }

        // Button: LAN Upload (QR Code)
        val btnLanUpload = android.widget.Button(context).apply {
            text = "扫码/局域网推流"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnLanUpload)

        container.addView(actionLayout)
        builder.setView(container)

        builder.setPositiveButton("确定") { _, _ ->
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val newEpg = com.iptv.tvplayer.data.Subscription(name, url)
                val currentEpgs = com.iptv.tvplayer.data.SettingsManager.epgs.toMutableList()
                currentEpgs.add(newEpg)
                com.iptv.tvplayer.data.SettingsManager.epgs = currentEpgs
                com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = url
                
                android.widget.Toast.makeText(context, "已添加并切换至$name", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 2) {
                    showSettingsLevel(2)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(context, "名称或URL不能为空", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }

        val alertDialog = builder.create()

        btnLanUpload.setOnClickListener {
            alertDialog.dismiss()
            checkAndCreateFolders(forcePrompt = true)
            val ip = getLocalIpAddress()
            showQrDialog("http://$ip:8080", "扫码添加/推送EPG")
        }

        alertDialog.show()
    }

    private fun String.fixEncoding(): String {
        if (this.all { it.code <= 255 }) {
            try {
                val bytes = this.toByteArray(Charsets.ISO_8859_1)
                val utf8 = String(bytes, Charsets.UTF_8)
                if (!utf8.contains('\uFFFD')) return utf8
                val gbk = String(bytes, java.nio.charset.Charset.forName("GBK"))
                if (!gbk.contains('\uFFFD')) return gbk
            } catch (e: Exception) {}
        }
        return this
    }

    private fun importLocalPlaylist(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val content = inputStream.use { it.readBytes() }
                val fileName = "local_playlist_${System.currentTimeMillis()}.txt"
                val targetFile = java.io.File(filesDir, fileName)
                targetFile.writeBytes(content)
                
                // 多路径获取文件名
                // 1. content:// URI 通过 ContentResolver DISPLAY_NAME
                // 2. file:// URI 则直接取文件名
                // 3. URI 最后一段路径
                // 4. 兜底：时间戳
                var originalName = ""

                val uriStr = uri.toString()
                when {
                    uri.scheme == "content" -> {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                val displayName = cursor.getString(nameIndex)?.trim()
                                if (!displayName.isNullOrBlank()) {
                                    originalName = displayName
                                }
                            }
                        }
                        // content URI 没有 DISPLAY_NAME 时，尝试从 URI path 中取最后一段
                        if (originalName.isEmpty()) {
                            originalName = uri.lastPathSegment?.substringAfterLast("/")
                                ?.substringAfterLast("%2F") ?: ""
                        }
                    }
                    uri.scheme == "file" -> {
                        originalName = uri.lastPathSegment ?: ""
                    }
                    else -> {
                        originalName = uri.lastPathSegment ?: uriStr.substringAfterLast("/")
                    }
                }

                // 去掉扩展名
                originalName = originalName
                    .removeSuffix(".m3u8").removeSuffix(".m3u").removeSuffix(".txt").trim()

                // 最终兜底：用时间戳保证唯一
                if (originalName.isEmpty()) {
                    originalName = "导入列表_${java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}"
                }

                
                val localUrl = "file://${targetFile.absolutePath}"
                val newSub = com.iptv.tvplayer.data.Subscription(originalName, localUrl)
                
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                currentSubs.add(newSub)
                com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                
                val currentActive = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls.toMutableSet()
                currentActive.add(localUrl)
                com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls = currentActive
                
                android.widget.Toast.makeText(this, "导入成功，已切换至$originalName", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 1) {
                    showSettingsLevel(1)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(this, "无法读取文件数据", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    private fun getCurrentVersionName(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun checkAppUpdate(manual: Boolean) {
        kotlinx.coroutines.MainScope().launch {
            try {
                val updateJsonStr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("https://gh-proxy.com/https://raw.githubusercontent.com/sfee1212/moyutv/main/update.json")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    if (connection.responseCode == 200) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else null
                }
                
                if (updateJsonStr != null) {
                    val jsonObj = org.json.JSONObject(updateJsonStr)
                    val serverVersionCode = jsonObj.optInt("versionCode", 0)
                    val serverVersionName = jsonObj.optString("versionName", "1.0")
                    val updateUrl = jsonObj.optString("updateUrl", "")
                    val updateInfo = jsonObj.optString("updateInfo", "发现新版本，请更新")
                    
                    val currentVersionCode = try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            pInfo.versionCode
                        }
                    } catch (e: Exception) { 1 }

                    if (serverVersionCode > currentVersionCode && updateUrl.isNotEmpty()) {
                        showUpdateDialog(serverVersionName, updateInfo, updateUrl)
                    } else {
                        if (manual) {
                            android.widget.Toast.makeText(this@MainActivity, "当前已是最新版本($serverVersionName)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (manual) {
                        android.widget.Toast.makeText(this@MainActivity, "暂无更新或获取更新失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (manual) {
                    android.widget.Toast.makeText(this@MainActivity, "网络错误，无法检查更新", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdateDialog(versionName: String, info: String, url: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本: $versionName")
            .setMessage(info)
            .setCancelable(true)
            .setPositiveButton("立即更新") { _, _ ->
                checkInstallPermissionAndDownload(url)
            }
            .setNegativeButton("稍后再看", null)
            .show()
    }

    private fun checkInstallPermissionAndDownload(url: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(this, "请在设置中允许安装未知应用", android.widget.Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        startUpdateDownload(url)
    }

    private fun startUpdateDownload(url: String) {
        val downloadUrl = if ((url.contains("github.com") || url.contains("githubusercontent.com")) && !url.contains("gh-proxy.com")) {
            "https://gh-proxy.com/$url"
        } else {
            url
        }
        val context = this
        val progressDialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        val tvProgress = android.widget.TextView(context).apply {
            text = "正在下载更新: 0%"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        progressDialogView.addView(tvProgress)
        progressDialogView.addView(progressBar)

        val progressDialog = android.app.AlertDialog.Builder(context)
            .setTitle("下载更新")
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        kotlinx.coroutines.MainScope().launch {
            try {
                val apkFile = java.io.File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var input: java.io.InputStream? = null
                    var output: java.io.OutputStream? = null
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        val connUrl = java.net.URL(downloadUrl)
                        conn = connUrl.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.connect()

                        if (conn.responseCode != 200) return@withContext false
                        val fileLength = conn.contentLength

                        input = conn.inputStream
                        output = java.io.FileOutputStream(apkFile)

                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val percentage = (total * 100 / fileLength).toInt()
                                runOnUiThread {
                                    progressBar.progress = percentage
                                    tvProgress.text = "正在下载更新: $percentage%"
                                }
                            }
                            output.write(data, 0, count)
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    } finally {
                        output?.close()
                        input?.close()
                        conn?.disconnect()
                    }
                }

                progressDialog.dismiss()

                if (success && apkFile.exists()) {
                    installApk(apkFile)
                } else {
                    android.widget.Toast.makeText(this@MainActivity, "操作失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                progressDialog.dismiss()
                android.widget.Toast.makeText(context, "下载更新出错: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installApk(file: java.io.File) {
        try {
            val authority = "$packageName.fileprovider"
            val apkUri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteSubscriptionConfirmDialog(item: com.iptv.tvplayer.ui.SettingItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除订阅源【${item.name}】吗")
            .setPositiveButton("删除") { _, _ ->
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                val targetSub = currentSubs.find { it.url == item.identifier }
                if (targetSub != null) {
                    currentSubs.remove(targetSub)
                    com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                    
                    // Clean up local file if it is stored in filesDir
                    if (item.identifier.startsWith("file://")) {
                        val path = item.identifier.replace("file://", "")
                        val localFile = java.io.File(path)
                        if (localFile.exists() && path.contains(filesDir.absolutePath)) {
                            localFile.delete()
                        }
                    }

                    // If we deleted an active one, update
                    val currentActive = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls.toMutableSet()
                    if (currentActive.contains(item.identifier)) {
                        currentActive.remove(item.identifier)
                        if (currentActive.isEmpty()) {
                            val fallbackUrl = currentSubs.firstOrNull()?.url ?: "http://129.159.177.216/api/test"
                            currentActive.add(fallbackUrl)
                        }
                        com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrls = currentActive
                        loadPlaylist()
                    }
                    
                    android.widget.Toast.makeText(this, "订阅源已删除", android.widget.Toast.LENGTH_SHORT).show()
                    showSettingsLevel(1) // refresh list
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteEpgConfirmDialog(item: com.iptv.tvplayer.ui.SettingItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除EPG【${item.name}】吗")
            .setPositiveButton("删除") { _, _ ->
                val currentEpgs = com.iptv.tvplayer.data.SettingsManager.epgs.toMutableList()
                val targetEpg = currentEpgs.find { it.url == item.identifier }
                if (targetEpg != null) {
                    currentEpgs.remove(targetEpg)
                    com.iptv.tvplayer.data.SettingsManager.epgs = currentEpgs
                    
                    // If we deleted the active one, fallback
                    if (com.iptv.tvplayer.data.SettingsManager.activeEpgUrl == item.identifier) {
                        val fallbackUrl = currentEpgs.firstOrNull()?.url ?: "https://gitee.com/taksssss/tv/raw/main/epg/erw.xml.gz"
                        com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = fallbackUrl
                        loadPlaylist()
                    }
                    
                    android.widget.Toast.makeText(this, "EPG源已删除", android.widget.Toast.LENGTH_SHORT).show()
                    showSettingsLevel(2) // refresh list
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private lateinit var tvNumberSelectOverlay: android.widget.TextView
    private var numberInputBuffer = ""
    private val runConfirmNumberSelection = Runnable { confirmNumberSelection() }

    private fun initNumberSelectOverlay() {
        tvNumberSelectOverlay = android.widget.TextView(this).apply {
            visibility = View.GONE
            textSize = 64f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#CC000000"))
                cornerRadius = 32f
            }
            background = gd
            gravity = android.view.Gravity.CENTER
            setPadding(80, 40, 80, 40)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        osdContainer.addView(tvNumberSelectOverlay)
    }

    private fun appendNumberInput(digit: String) {
        handler.removeCallbacks(runConfirmNumberSelection)
        if (numberInputBuffer.length >= 4) return
        numberInputBuffer += digit
        tvNumberSelectOverlay.text = numberInputBuffer
        tvNumberSelectOverlay.visibility = View.VISIBLE
        handler.postDelayed(runConfirmNumberSelection, 2000)
    }

    private fun confirmNumberSelection() {
        handler.removeCallbacks(runConfirmNumberSelection)
        tvNumberSelectOverlay.visibility = View.GONE
        val input = numberInputBuffer
        numberInputBuffer = ""
        if (input.isEmpty()) return
        val channelNum = input.toIntOrNull() ?: return
        if (channelNum <= 0) {
            android.widget.Toast.makeText(this, "无效的频道号", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val flatList = getFlatChannelList()
        if (channelNum > flatList.size) {
            android.widget.Toast.makeText(this, "无此频道，最大频道号：${flatList.size}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val (catIdx, chIdx) = flatList[channelNum - 1]
        val cat = categories.getOrNull(catIdx) ?: return
        val chName = channelsMap[cat]?.getOrNull(chIdx)?.name ?: ""
        android.widget.Toast.makeText(this, "数字选台: 切换至第 ${channelNum} 台: $chName", android.widget.Toast.LENGTH_SHORT).show()
        playChannel(catIdx, chIdx, 0)
    }

    private fun getFlatChannelList(): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        categories.forEachIndexed { catIdx, cat ->
            val chList = channelsMap[cat] ?: emptyList()
            chList.forEachIndexed { chIdx, _ ->
                list.add(Pair(catIdx, chIdx))
            }
        }
        return list
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && ip != "0.0.0.0" && ip != "127.0.0.1") {
                            return ip
                        }
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun checkAndCreateFolders(forcePrompt: Boolean = false) {
        val createDirs = {
            try {
                val base = java.io.File(android.os.Environment.getExternalStorageDirectory(), "摸鱼TV")
                java.io.File(base, "list").mkdirs()
                java.io.File(base, "js").mkdirs()
                java.io.File(base, "php").mkdirs() // PHP 解析脚本
                java.io.File(base, "py").mkdirs()  // Python 解析脚本
                java.io.File(base, "bin").mkdirs() // php 可执行文件覆盖放置目录(可选, 已内置)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                // 只在首次启动或用户主动触发时跳系统设置页, 避免每次启动都弹
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                if (!forcePrompt && prefs.getBoolean("all_files_prompted", false)) return
                prefs.edit().putBoolean("all_files_prompted", true).apply()

                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(
                            this,
                            "当前系统没有所有文件访问权限设置页，请手动在系统设置中授权存储权限",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                createDirs()
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
            } else {
                createDirs()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val base = java.io.File(android.os.Environment.getExternalStorageDirectory(), "摸鱼TV")
                java.io.File(base, "list").mkdirs()
                java.io.File(base, "js").mkdirs()
                java.io.File(base, "php").mkdirs()
                java.io.File(base, "py").mkdirs()
                java.io.File(base, "bin").mkdirs()
            } catch (e: Exception) {}
        }
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showInternalFilePicker()
        }
    }

    private fun showInternalFilePicker() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                android.widget.Toast.makeText(this, "请先授予所有文件管理权限", android.widget.Toast.LENGTH_SHORT).show()
                checkAndCreateFolders(forcePrompt = true)
                return
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002)
                return
            }
        }
        
        com.iptv.tvplayer.ui.LocalFilePickerDialog(this) { file ->
            val uri = android.net.Uri.fromFile(file)
            // 直接调用, 避免 release 混淆后反射找不到方法名
            importLocalPlaylist(uri)
        }.show()
    }

    private fun supportsCatchup(channel: com.iptv.tvplayer.data.Channel?): Boolean {
        if (channel == null) return false
        if (!channel.catchup.isNullOrBlank() || !channel.catchupSource.isNullOrBlank() || channel.catchupDays > 0) {
            return true
        }
        return channel.urls.any { url -> 
            url.contains("/PLTV/") || url.contains("/TVOD/") || url.contains("/tvod/") || url.contains("playseek=") || url.contains(".sc96655.com") || url.contains(".omd.")
        }
    }

    private fun showCatchupPanelForChannel(channel: com.iptv.tvplayer.data.Channel) {
        catchupFocusedChannel = channel
        
        val programsByDay = com.iptv.tvplayer.data.EpgManager.getProgramsGroupedByDay(channel.name)
        val daysList = programsByDay.keys.toList()
        
        if (daysList.isNotEmpty()) {
            currentCatchupDay = daysList.first()
            catchupDayAdapter.updateData(daysList)
            catchupProgramAdapter.updateData(programsByDay[currentCatchupDay] ?: emptyList())
            
            // Highlight the currently playing catchup program if it belongs to this channel
            if (playingCatchupTitle != null) {
                catchupProgramAdapter.setPlayingTitle(playingCatchupTitle)
            }
            
            catchupPanel.visibility = View.VISIBLE
            catchupHintTab.visibility = View.GONE
            rvCatchupDays.post {
                val layoutManager = rvCatchupDays.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val targetView = layoutManager?.findViewByPosition(0)
                if (targetView != null) {
                    targetView.requestFocus()
                } else {
                    rvCatchupDays.requestFocus()
                }
            }
        } else {
            android.widget.Toast.makeText(this, "暂无该频道的回看信息", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun playCatchupProgram(program: com.iptv.tvplayer.data.EpgProgram) {
        val channel = catchupFocusedChannel ?: return
        
        val urls = channel.urls
        var selectedUrl = urls.firstOrNull() ?: return

        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val startStr = fmt.format(java.util.Date(program.startMillis))
        val endStr = fmt.format(java.util.Date(program.endMillis))

        var modifiedUrl = selectedUrl.replace("livemode=1", "livemode=4")
        modifiedUrl = modifiedUrl.replace("/180000001001/", "/180000002001/")
        modifiedUrl = modifiedUrl.replace("/180000001002/", "/180000002002/")
        modifiedUrl = modifiedUrl.replace("/200000001898/", "/200000002898/")

        val isM3u8 = modifiedUrl.contains(".m3u8", ignoreCase = true)
        val usePlayseek = !isM3u8 || modifiedUrl.contains("/PLTV/", ignoreCase = true) || modifiedUrl.contains("/TVOD/", ignoreCase = true) || modifiedUrl.contains("/tvod/", ignoreCase = true) || modifiedUrl.contains(".sc96655.com", ignoreCase = true) || modifiedUrl.contains(".omd.", ignoreCase = true)
        val playseekStr = "${startStr}-${endStr}"
        val catchupUrl = if (!usePlayseek) {
            if (modifiedUrl.contains("?")) {
                "$modifiedUrl&starttime=$startStr&endtime=$endStr"
            } else {
                "$modifiedUrl?starttime=$startStr&endtime=$endStr"
            }
        } else {
            if (modifiedUrl.contains("?")) {
                "$modifiedUrl&playseek=$playseekStr"
            } else {
                "$modifiedUrl?playseek=$playseekStr"
            }
        }

        playingCatchupTitle = program.title
        playingCatchupProgram = program
        catchupProgramAdapter.setPlayingTitle(program.title)
        
        // Setup Player
        com.iptv.tvplayer.player.NativePlayerManager.release()
        for (i in playerContainer.childCount - 1 downTo 0) {
            val child = playerContainer.getChildAt(i)
            if (child is com.tencent.smtt.sdk.WebView) {
                playerContainer.removeView(child)
                child.destroy()
            } else {
                playerContainer.removeView(child)
            }
        }
        
        val isDrm = !channel.drmType.isNullOrBlank() && !channel.drmKey.isNullOrBlank()
        var decoderType = if (isDrm) "EXO" else com.iptv.tvplayer.data.SettingsManager.decoderTypeState.value
        val lowerUrl = catchupUrl.lowercase()
        if (decoderType == "EXO" && (lowerUrl.contains("avs3") || lowerUrl.contains("av3a"))) {
            decoderType = "MPV"
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "检测到 AVS3 流，已自动切换至 MPV 内核播放", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (decoderType == "EXO" && (lowerUrl.startsWith("rtsp://") ||
                Regex("^(sk|socks5|proxy)://[^/\\uFF06]+[/&\\uFF06]rtsp://", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl))) {
            decoderType = "MPV"
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "RTSP 流已自动切换至 MPV 内核播放", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        val playerView = if (decoderType == "EXO") {
            com.iptv.tvplayer.player.NativePlayerManager.createExoPlayer(this@MainActivity, catchupUrl, channel.drmType, channel.drmKey)
        } else {
            com.iptv.tvplayer.player.NativePlayerManager.createMPVPlayer(this@MainActivity, catchupUrl)
        }
        
        playerView.visibility = View.VISIBLE
        playerContainer.addView(playerView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        tvStatus.text = "加载回看..."
        tvStatus.visibility = View.VISIBLE
        findViewById<android.widget.ProgressBar>(R.id.buffering_spinner).visibility = View.VISIBLE
        
        com.iptv.tvplayer.player.NativePlayerManager.onPlayerReady = {
            runOnUiThread {
                val currentView = com.iptv.tvplayer.player.NativePlayerManager.getCurrentView()
                for (i in playerContainer.childCount - 1 downTo 0) {
                    val child = playerContainer.getChildAt(i)
                    if (child != currentView) {
                        playerContainer.removeView(child)
                    }
                }
                
                tvStatus.visibility = View.GONE
                findViewById<android.widget.ProgressBar>(R.id.buffering_spinner).visibility = View.GONE
            }
        }
        
        com.iptv.tvplayer.player.NativePlayerManager.onPlayerBuffering = {
            runOnUiThread {
                // Do nothing to keep screen clean
            }
        }
        
        // Hide UI
        catchupPanel.visibility = View.GONE
        menuOverlay.visibility = View.GONE
        playerContainer.requestFocus()
    }

    override fun onDestroy() {
        playJob?.cancel()
        try {
            localServer?.stop()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "stop local server failed: ${t.message}")
        }
        localServer = null
        try {
            com.iptv.tvplayer.player.NativePlayerManager.release()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "release player failed: ${t.message}")
        }
        super.onDestroy()
    }
}



