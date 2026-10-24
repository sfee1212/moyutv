package com.iptv.tvplayer.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─── 数据模型 ─────────────────────────────────────────────────────────────────

data class DayWeather(
    val maxTempC: Int,
    val minTempC: Int,
    val weatherCode: Int,
    val desc: String       // 中文天气描述
)

data class WeatherInfo(
    val currentTempC: Int,
    val currentCode: Int,
    val currentDesc: String,
    val cityName: String,
    val today: DayWeather,
    val tomorrow: DayWeather
)

// ─── 天气代码 -> 中文描述 + Emoji ─────────────────────────────────────────────

private fun codeToDesc(code: Int): String = when (code) {
    113 -> "晴"
    116 -> "少云"
    119 -> "多云"
    122 -> "阴"
    143 -> "薄雾"
    176 -> "局部小雨"
    179 -> "局部小雪"
    182 -> "局部冻雨"
    185 -> "局部冰雹"
    200 -> "雷阵雨"
    227 -> "小雪"
    230 -> "暴雪"
    248 -> "雾"
    260 -> "冻雾"
    263 -> "小阵雨"
    266 -> "小雨"
    281 -> "冻毛毛雨"
    284 -> "冻毛毛雨"
    293 -> "局部小雨"
    296 -> "小雨"
    299 -> "中阵雨"
    302 -> "中雨"
    305 -> "大阵雨"
    308 -> "大雨"
    311 -> "冻细雨"
    314 -> "冻大细雨"
    317 -> "小雨夹雪"
    320 -> "中雨夹雪"
    323 -> "局部小雪"
    326 -> "小雪"
    329 -> "局部中雪"
    332 -> "中雪"
    335 -> "局部大雪"
    338 -> "大雪"
    350 -> "冰雹"
    353 -> "小阵雨"
    356 -> "中到大阵雨"
    359 -> "暴雨"
    362 -> "小雨夹雪"
    365 -> "中到大雨夹雪"
    368 -> "小阵雪"
    371 -> "中到大阵雪"
    374 -> "小冰粒阵雨"
    377 -> "中到大冰粒阵雨"
    386 -> "局部雷阵雨"
    389 -> "大雷阵雨"
    392 -> "局部雷阵雪"
    395 -> "大雷阵雪"
    else -> "未知"
}

fun codeToEmoji(code: Int): String = when (code) {
    113 -> "☀️"
    116 -> "🌤️"
    119, 122 -> "☁️"
    143, 248, 260 -> "🌫️"
    176, 263, 266, 293, 296, 353 -> "🌦️"
    179, 227, 230, 323, 326, 329, 332, 335, 338, 368, 371 -> "❄️"
    182, 185, 281, 284, 311, 314, 350, 374, 377 -> "🌨️"
    200, 386, 389 -> "⛈️"
    299, 302, 305, 308, 356, 359 -> "🌧️"
    317, 320, 362, 365 -> "🌨️"
    392, 395 -> "⛈️"
    else -> "🌡️"
}

// ─── WeatherManager ──────────────────────────────────────────────────────────

object WeatherManager {
    private const val TAG = "WeatherManager"
    private const val API_URL = "https://wttr.in/?format=j1&lang=zh"

    private val _weatherInfo = MutableStateFlow<WeatherInfo?>(null)
    val weatherInfoFlow = _weatherInfo.asStateFlow()

    val weatherInfo: WeatherInfo?
        get() = _weatherInfo.value

    /** 拉取天气，协程中调用，出错时更新状态*/
    suspend fun fetchWeather() {
        withContext(Dispatchers.IO) {
            try {
                val apiUrl = "https://wttr.in/?format=j1&lang=zh"

                val conn = java.net.URL(apiUrl).openConnection() as HttpURLConnection

                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "curl/7.68.0")
                conn.connect()

                if (conn.responseCode != 200) {
                    Log.w(TAG, "HTTP ${conn.responseCode}")
                    _weatherInfo.value = _weatherInfo.value?.copy(cityName = "网络超时(${conn.responseCode})")
                    return@withContext
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val current = json.getJSONArray("current_condition").getJSONObject(0)
                val currentTempC = current.getString("temp_C").toIntOrNull() ?: 0
                val currentCode = current.getString("weatherCode").toIntOrNull() ?: 113

                val nearestArea = json.getJSONArray("nearest_area").getJSONObject(0)
                val cityName = nearestArea.getJSONArray("areaName")
                    .getJSONObject(0).getString("value")

                val weatherArr = json.getJSONArray("weather")
                fun parseDayWeather(idx: Int): DayWeather {
                    val day = weatherArr.getJSONObject(idx)
                    val maxTemp = day.getString("maxtempC").toIntOrNull() ?: 0
                    val minTemp = day.getString("mintempC").toIntOrNull() ?: 0
                    val hourly = day.getJSONArray("hourly")
                    val noonIdx = minOf(4, hourly.length() - 1)
                    val noon = hourly.getJSONObject(noonIdx)
                    val code = noon.getString("weatherCode").toIntOrNull() ?: 113
                    return DayWeather(maxTemp, minTemp, code, codeToDesc(code))
                }

                val today = parseDayWeather(0)
                val tomorrow = if (weatherArr.length() > 1) parseDayWeather(1) else today

                _weatherInfo.value = WeatherInfo(
                    currentTempC = currentTempC,
                    currentCode = currentCode,
                    currentDesc = codeToDesc(currentCode),
                    cityName = cityName,
                    today = today,
                    tomorrow = tomorrow
                )

                Log.i(TAG, "天气更新成功: $cityName ${currentTempC}°C ${codeToDesc(currentCode)}")

            } catch (e: Exception) {
                Log.e(TAG, "天气获取失败: ${e.message}")
                _weatherInfo.value = _weatherInfo.value?.copy(cityName = "获取失败")
            }
        }
    }
}
