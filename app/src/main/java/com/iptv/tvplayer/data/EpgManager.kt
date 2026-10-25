package com.iptv.tvplayer.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.collections.HashMap

data class EpgProgram(
    val title: String,
    val startTimeStr: String,
    val endTimeStr: String,
    val startMillis: Long,
    val endMillis: Long
)

object EpgManager {
    private val TAG = "EpgManager"

    /**
     * Opens an HttpURLConnection with SSL verification disabled.
     * Needed because LDPlayer's network sometimes intercepts HTTPS with a mismatched certificate
     * (e.g., Facebook cert for epg.v1.mk), causing SSLPeerUnverifiedException.
     * EPG data is not sensitive, so this trade-off is acceptable.
     */
    private fun openTrustAllConnection(url: URL): HttpURLConnection {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }
    // 解析在 IO 线程进行, UI 线程会同时读取。整体替换不可变 Map, 避免
    // ConcurrentModificationException 以及读到"清空后只填了一半"的中间状态。
    @Volatile
    private var channelToPrograms: Map<String, List<EpgProgram>> = emptyMap()

    @Volatile
    private var channelToLogo: Map<String, String> = emptyMap()

    val activeChannelNames = mutableSetOf<String>()

    @Volatile
    private var lastUpdateMillis = 0L
    private const val CACHE_FILE_NAME = "epg_cache.xml"

    suspend fun loadEpg(context: android.content.Context) = withContext(Dispatchers.IO) {
        val epgUrl = SettingsManager.epgUrl
        Log.d(TAG, "loadEpg called, url=$epgUrl")
        if (epgUrl.isEmpty()) {
            Log.d(TAG, "EPG url is empty, skipping")
            return@withContext
        }

        val localActiveNames = synchronized(activeChannelNames) {
            activeChannelNames.toSet()
        }
        Log.d(TAG, "Active channel count: ${localActiveNames.size}")

        val cacheFileName = "epg_cache_${epgUrl.hashCode()}.xml"
        val cacheFile = File(context.cacheDir, cacheFileName)
        Log.d(TAG, "Cache file: ${cacheFile.absolutePath}, exists=${cacheFile.exists()}, size=${if (cacheFile.exists()) cacheFile.length() else 0}")
        
        // Clean up old caches
        context.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("epg_cache") && it.name != cacheFileName) {
                Log.d(TAG, "Deleting old cache: ${it.name}")
                it.delete()
            }
        }

        // Check if cache is younger than 24 hours
        if (cacheFile.exists() && cacheFile.length() > 1000 && System.currentTimeMillis() - cacheFile.lastModified() < 24 * 60 * 60 * 1000) {
            Log.d(TAG, "Using cached EPG file (${cacheFile.length()} bytes)")
            try {
                parseXml(FileInputStream(cacheFile), localActiveNames, detectXmlCharset(cacheFile))
                Log.d(TAG, "Parsed from cache, programs map size: ${channelToPrograms.size}")
                return@withContext
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse cached EPG, deleting cache", e)
                cacheFile.delete()
            }
        }

        // Download and parse
        // NOTE: Do NOT use ProxyManager.resolveUrl() for EPG ?EPG is plain XML data, not
        // stream content that needs P2P/SOCKS5 proxying. The proxy may reject or mangle EPG URLs.
        // Try the user's configured URL first, then fallback URLs if it fails.
        // Fallback priority: cdn.loc.cc (4.4MB, works!), 112114.xyz, v1.mk
        val fallbackUrls = listOf(
            "https://epg.cdn.loc.cc/xml",
            "http://epg.112114.xyz/pp.xml",
            "https://epg.v1.mk/fy.xml"
        )
        val urlsToTry = listOf(epgUrl) + fallbackUrls.filter { it != epgUrl }

        var downloaded = false
        for (tryUrl in urlsToTry) {
            if (downloaded) break
            Log.d(TAG, "Trying EPG URL: $tryUrl")
            try {
                var url = URL(tryUrl)
                var connection = openTrustAllConnection(url)
                var redirectCount = 0
                while (true) {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 90000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                    connection.instanceFollowRedirects = false
                    val status = connection.responseCode
                    Log.d(TAG, "HTTP status: $status for $url")
                    if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                        val newUrl = connection.getHeaderField("Location")
                        Log.d(TAG, "Redirect to: $newUrl")
                        if (newUrl == null || redirectCount > 5) break
                        url = URL(url, newUrl)
                        connection = openTrustAllConnection(url)
                        redirectCount++
                    } else {
                        break
                    }
                }

                val finalStatus = connection.responseCode
                if (finalStatus != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "EPG download failed with HTTP $finalStatus for $tryUrl, trying next...")
                    continue
                }

                // Detect gzip: check Content-Encoding, URL suffix, or magic bytes (0x1F 0x8B)
                // Some servers (e.g. epg.cdn.loc.cc) send gzip without Content-Encoding header
                val rawStream = connection.inputStream.buffered()
                val encoding = connection.getHeaderField("Content-Encoding")
                rawStream.mark(4)
                val magic = ByteArray(4).also { rawStream.read(it) }
                rawStream.reset()
                val isGzip = (encoding != null && encoding.lowercase(Locale.ENGLISH).contains("gzip"))
                    || url.toString().endsWith(".gz", ignoreCase = true)
                    || (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte())
                val inputStream: InputStream = if (isGzip) {
                    Log.d(TAG, "Decompressing gzip stream for $tryUrl")
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }

                // Save decompressed XML to cache
                Log.d(TAG, "Saving EPG to cache: ${cacheFile.absolutePath}")
                cacheFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved ${cacheFile.length()} bytes to cache (plain XML), now parsing...")

                try {
                    // Fix unescaped & characters (line-by-line, no OOM risk)
                    fixXmlEntities(cacheFile, detectXmlCharset(cacheFile))
                    parseXml(FileInputStream(cacheFile), localActiveNames, detectXmlCharset(cacheFile))
                    Log.d(TAG, "Parsing done! Programs map size: ${channelToPrograms.size}")
                    downloaded = true
                } catch (e: Exception) {
                    Log.e(TAG, "XML parse failed for $tryUrl: ${e.javaClass.name}: ${e.message}", e)
                    cacheFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "EPG download error for $tryUrl: ${e.javaClass.name}: ${e.message}")
            }
        }
        if (!downloaded) {
            Log.e(TAG, "All EPG URLs failed to download")
        }
    }

    /**
     * 检测 XML 编码: BOM 优先, 其次 XML 声明里的 encoding=。
     * 很多国内 EPG 源是 GBK/GB18030, 统一按 UTF-8 解析会得到乱码节目名。
     */
    private fun detectXmlCharset(file: File): String {
        return try {
            val head = ByteArray(512)
            val read = FileInputStream(file).use { it.read(head) }
            if (read >= 2) {
                if (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return "UTF-16LE"
                if (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return "UTF-16BE"
            }
            val text = String(head, 0, read.coerceAtLeast(0), Charsets.ISO_8859_1)
            val match = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(text)
            val encoding = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (encoding.isNotEmpty() && Charset.isSupported(encoding)) encoding else "UTF-8"
        } catch (_: Throwable) {
            "UTF-8"
        }
    }

    private fun fixXmlEntities(file: File, charsetName: String = "UTF-8") {
        try {
            val charset = try {
                Charset.forName(charsetName)
            } catch (_: Throwable) {
                Charsets.UTF_8
            }
            val regex = Regex("&(?!(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)")
            val tempFile = File(file.parent, file.name + ".tmp")
            var anyFixed = false
            file.bufferedReader(charset).use { reader ->
                tempFile.bufferedWriter(charset).use { writer ->
                    reader.forEachLine { line ->
                        val fixed = line.replace(regex, "&amp;")
                        if (fixed != line) anyFixed = true
                        writer.write(fixed)
                        writer.newLine()
                    }
                }
            }
            if (anyFixed) {
                Log.d(TAG, "Fixed XML entity escaping ?replacing ${file.name}")
                // Rename temp over original
                file.delete()
                tempFile.renameTo(file)
            } else {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixXmlEntities failed: ${e.message}")
        }
    }


    private fun convertToSimplified(name: String): String {
        val trad = "亞衛國視劇鳳華東動無線紀錄樂體戲聞資訊綜合粵語電影風雲兒財經環鷹廣臺灣門蓮陽驚懸歷軍農業學選產區畫藝際發現實時"
        val simp = "亚卫国视剧凤华东动无线纪录乐体戏闻资讯综合粤语电影风云儿财经环鹰广台湾门莲阳惊悬历军农业学选产区画艺际发现实时"
        val sb = StringBuilder()
        for (char in name) {
            val idx = trad.indexOf(char)
            if (idx != -1) {
                sb.append(simp[idx])
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun normalizeChannelName(name: String): String {
        val cleanName = name.replace(Regex("^\\d+\\.\\s*"), "")
        val simplified = convertToSimplified(cleanName)
        return simplified.lowercase()
            .replace(Regex("[-_\\s]+"), "")
            .replace("hd", "")
            .replace("fhd", "")
            .replace("4k", "")
            .replace("高清", "")
            .replace("超清", "")
            .replace("标清", "")
            .replace("频道", "")
            .replace("中央", "cctv")
    }

    private fun findMatchingM3uNames(xmlChannelName: String, normalizedM3uToOriginals: Map<String, List<String>>): List<String> {
        val normXml = normalizeChannelName(xmlChannelName)
        if (normXml.isEmpty()) return emptyList()
        val matches = mutableListOf<String>()
        
        // 1. Exact match
        normalizedM3uToOriginals[normXml]?.let { matches.addAll(it) }
        
        // 2. Partial match (avoiding heavy loop operations by comparing keys)
        for ((normM3u, m3uNames) in normalizedM3uToOriginals) {
            if (normM3u == normXml) continue
            if (normM3u.startsWith(normXml) || normXml.startsWith(normM3u)) {
                val diff = if (normM3u.length > normXml.length) normM3u.substring(normXml.length) else normXml.substring(normM3u.length)
                if (diff.isNotEmpty() && !diff[0].isDigit()) {
                    matches.addAll(m3uNames)
                }
            }
        }
        return matches.distinct()
    }


    private fun parseXml(inputStream: InputStream, localActiveNames: Set<String>, charsetName: String = "UTF-8") {
        Log.d(TAG, "parseXml called, activeNames count=${localActiveNames.size}, charset=$charsetName")

        // 先构建到局部 Map, 解析完成后整体替换, 避免 UI 线程读到中间状态
        val newPrograms = HashMap<String, MutableList<EpgProgram>>()
        val newLogos = HashMap<String, String>()

        // Precompute and cache normalized M3U channel names once to avoid heavy repetitive regex computation
        val normalizedM3uToOriginals = HashMap<String, MutableList<String>>()
        for (m3uName in localActiveNames) {
            val norm = normalizeChannelName(m3uName)
            if (norm.isNotEmpty()) {
                normalizedM3uToOriginals.getOrPut(norm) { mutableListOf() }.add(m3uName)
            }
        }

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, charsetName)

            var eventType = parser.eventType
            var currentChannelId = ""
            var currentChannelName = ""
            
            var currentProgramId = ""
            var currentStartStr = ""
            var currentEndStr = ""
            var currentTitle = ""

            val xmlChannelIdToM3uNames = HashMap<String, List<String>>()
            val xmlChannelIdToLogo = HashMap<String, String>()


            // XMLTV date format: 20240528140000 +0800
            val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getDefault()
            
            // Fallback for missing timezone
            val sdfFallback = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
            sdfFallback.timeZone = TimeZone.getDefault()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("channel", ignoreCase = true)) {
                            currentChannelId = parser.getAttributeValue(null, "id") ?: ""
                            currentChannelName = ""
                        } else if (tagName.equals("display-name", ignoreCase = true)) {
                            if (currentChannelId.isNotEmpty()) {
                                currentChannelName = parser.nextText().trim()
                                if (currentChannelName.isNotEmpty()) {
                                    // display-name 只用于匹配 M3U 频道名
                                    val matches = findMatchingM3uNames(currentChannelName, normalizedM3uToOriginals)
                                    if (matches.isNotEmpty()) {
                                        val existing = xmlChannelIdToM3uNames[currentChannelId]
                                        if (existing == null) {
                                            xmlChannelIdToM3uNames[currentChannelId] = matches
                                        } else {
                                            xmlChannelIdToM3uNames[currentChannelId] = (existing + matches).distinct()
                                        }
                                    }
                                }
                            }
                        } else if (tagName.equals("logo", ignoreCase = true)) {
                            if (currentChannelId.isNotEmpty()) {
                                val logoUrl = parser.getAttributeValue(null, "src") ?: ""
                                if (logoUrl.isNotEmpty()) {
                                    xmlChannelIdToLogo[currentChannelId] = logoUrl
                                }
                            }
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            currentProgramId = parser.getAttributeValue(null, "channel") ?: ""
                            currentStartStr = parser.getAttributeValue(null, "start") ?: ""
                            currentEndStr = parser.getAttributeValue(null, "stop") ?: ""
                            currentTitle = ""
                        } else if (tagName.equals("title", ignoreCase = true) && currentProgramId.isNotEmpty()) {
                            currentTitle = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("programme", ignoreCase = true) && currentProgramId.isNotEmpty()) {
                            val m3uMatches = xmlChannelIdToM3uNames[currentProgramId]
                            if (m3uMatches != null && m3uMatches.isNotEmpty() && currentTitle.isNotEmpty()) {
                                try {
                                    val startMillis = parseTime(currentStartStr, sdf, sdfFallback)
                                    val endMillis = parseTime(currentEndStr, sdf, sdfFallback)
                                    
                                    val formattedStart = formatDisplayTime(startMillis)
                                    val formattedEnd = formatDisplayTime(endMillis)
                                    
                                    val program = EpgProgram(
                                        title = currentTitle,
                                        startTimeStr = formattedStart,
                                        endTimeStr = formattedEnd,
                                        startMillis = startMillis,
                                        endMillis = endMillis
                                    )
                                    
                                    for (m3uName in m3uMatches) {
                                        // Key by normalized name so lookup always works regardless of exact case/spacing
                                        val key = normalizeChannelName(m3uName)
                                        val list = newPrograms.getOrPut(key) { mutableListOf() }
                                        list.add(program)
                                    }
                                } catch (e: Exception) {
                                    // Ignore parse errors for single items
                                }
                            }
                            currentProgramId = ""
                        } else if (tagName.equals("channel", ignoreCase = true)) {
                            currentChannelId = ""
                        }
                    }
                }
                eventType = parser.next()
            }
            
            xmlChannelIdToLogo.forEach { (channelId, logoUrl) ->
                val m3uNames = xmlChannelIdToM3uNames[channelId]
                if (m3uNames != null) {
                    for (name in m3uNames) {
                        // Key by normalized name to match lookup
                        newLogos[normalizeChannelName(name)] = logoUrl
                    }
                }
            }

            // Sort programs by start time
            newPrograms.values.forEach { list ->
                list.sortBy { it.startMillis }
            }

            // 原子替换, UI 线程要么看到旧数据, 要么看到完整的新数据
            channelToPrograms = newPrograms.mapValues { it.value.toList() }
            channelToLogo = newLogos
            lastUpdateMillis = System.currentTimeMillis()
            Log.d(TAG, "parseXml complete: ${channelToPrograms.size} channels matched, total programs=${channelToPrograms.values.sumOf { it.size }}")
            // Log first few matched channel names for debugging
            channelToPrograms.keys.take(10).forEach { Log.d(TAG, "  matched: $it") }
        } finally {
            inputStream.close()
        }
    }

    private fun parseTime(timeStr: String, sdf: SimpleDateFormat, sdfFallback: SimpleDateFormat): Long {
        val text = timeStr.trim()
        // XMLTV 常见: 20240528140000 +0800 / 20240528140000+0800 / 20240528140000
        val match = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})\\s*([+-]\\d{4}|Z)?").find(text)
        if (match != null) {
            val values = match.groupValues
            val zone = when {
                values[7].equals("Z", ignoreCase = true) -> TimeZone.getTimeZone("GMT")
                values[7].isNotEmpty() -> TimeZone.getTimeZone("GMT${values[7]}")
                else -> TimeZone.getDefault()
            }
            return try {
                val calendar = Calendar.getInstance(zone)
                calendar.isLenient = false
                calendar.clear()
                calendar.set(
                    values[1].toInt(),
                    values[2].toInt() - 1,
                    values[3].toInt(),
                    values[4].toInt(),
                    values[5].toInt(),
                    values[6].toInt()
                )
                calendar.timeInMillis
            } catch (_: Exception) {
                0L
            }
        }
        return try {
            sdf.parse(text)?.time ?: 0L
        } catch (_: Exception) {
            try {
                if (text.length >= 14) {
                    sdfFallback.parse(text.substring(0, 14))?.time ?: 0L
                } else 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
    
    private fun formatDisplayTime(millis: Long): String {
        if (millis <= 0) return ""
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun getCurrentAndNextProgram(channelName: String): Pair<EpgProgram?, EpgProgram?> {
        // Normalize the input name to match how we stored entries in channelToPrograms
        val key = normalizeChannelName(channelName)
        val programs = channelToPrograms[key] ?: return Pair(null, null)
        val now = System.currentTimeMillis()
        
        var current: EpgProgram? = null
        var next: EpgProgram? = null
        
        for (i in programs.indices) {
            val p = programs[i]
            if (now in p.startMillis..p.endMillis) {
                current = p
                if (i + 1 < programs.size) {
                    next = programs[i + 1]
                }
                break
            } else if (p.startMillis > now) {
                if (current == null) {
                    next = p
                }
                break
            }
        }
        return Pair(current, next)
    }

    fun getChannelLogo(channelName: String): String? {
        return channelToLogo[normalizeChannelName(channelName)]
    }

    /**
     * Returns programs for a channel, grouped by day (e.g. "今天 07-01", "昨天 06-30").
     * Sorted from newest day to oldest day, and within each day from earliest to latest.
     */
    fun getProgramsGroupedByDay(channelName: String, maxDays: Int = 7): LinkedHashMap<String, List<EpgProgram>> {
        val key = normalizeChannelName(channelName)
        val programs = channelToPrograms[key] ?: return LinkedHashMap()
        val now = System.currentTimeMillis()
        
        val calendar = Calendar.getInstance()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Group by day difference
        val grouped = programs.filter { it.startMillis <= now } // Only show programs that have started
            .groupBy { 
                val diffMillis = todayStart - it.startMillis
                if (it.startMillis >= todayStart) 0
                else (diffMillis / (24 * 60 * 60 * 1000) + 1).toInt()
            }
            .filter { it.key in 0 until maxDays }
        
        val result = LinkedHashMap<String, List<EpgProgram>>()
        val sdf = SimpleDateFormat("MM-dd", Locale.getDefault())
        
        for (dayOffset in 0 until maxDays) {
            val list = grouped[dayOffset]
            if (!list.isNullOrEmpty()) {
                val label = when (dayOffset) {
                    0 -> "今天"
                    1 -> "昨天"
                    2 -> "前天"
                    else -> "${dayOffset}天前"
                }
                calendar.timeInMillis = todayStart - (dayOffset * 24L * 60 * 60 * 1000)
                val dateStr = sdf.format(calendar.time)
                result["$label $dateStr"] = list
            }
        }
        
        return result
    }
}
