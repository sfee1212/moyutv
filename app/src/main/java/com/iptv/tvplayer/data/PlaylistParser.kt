package com.iptv.tvplayer.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCrypt {
    // Obfuscated keys using XOR mask 0x5A
    private val oK = byteArrayOf(14, 44, 10, 54, 59, 35, 63, 40, 104, 106, 104, 108, 26, 17, 63, 35)
    private val oI = byteArrayOf(14, 44, 10, 54, 59, 35, 63, 40, 104, 106, 104, 108, 26, 19, 12, 123)
    private const val M = 0x5A.toByte()

    private fun dK(): ByteArray = oK.map { (it.toInt() xor M.toInt()).toByte() }.toByteArray()
    private fun dI(): ByteArray = oI.map { (it.toInt() xor M.toInt()).toByte() }.toByteArray()

    fun decrypt(encryptedBase64: String): String? {
        return try {
            val decodedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(dK(), "AES")
            val ivSpec = IvParameterSpec(dI())
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * 播放列表解析 (TXT / M3U)。
 *
 * 兼容性处理:
 *  - UTF-8 BOM / GB18030 / GBK / BIG5 编码
 *  - gzip 压缩的远程列表
 *  - `url#headers#{...}` 请求头 (JSON, 含逗号/冒号不再解析失败)
 *  - M3U: group-title / tvg-logo / catchup / catchup-source / catchup-days /
 *         timeshift / #EXTGRP / KODIPROP
 *  - TXT: 分类名,#genre#、`频道,url1#url2` 多线路、KODIPROP 跨行
 *  - 大列表使用索引合并, 避免 O(n²) 查找
 */
object PlaylistParser {
    @Volatile
    var lastError = ""

    private val urlSchemeRegex = Regex(
        "^(?:https?|rtsp|rtmps|rtmp|rtp|udp|file|local|content|ftp|mms)://",
        RegexOption.IGNORE_CASE
    )

    private fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var offset = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            offset = 3
        }
        val data = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes

        try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) {
        }

        for (charsetName in arrayOf("GB18030", "GBK", "BIG5")) {
            try {
                return String(data, Charset.forName(charsetName))
            } catch (_: Exception) {
            }
        }
        return String(data, Charsets.UTF_8)
    }

    private fun parseHeaders(headerJson: String?): Map<String, String> {
        if (headerJson.isNullOrBlank()) return emptyMap()
        val text = headerJson.trim()
        try {
            val obj = JSONObject(text)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
            return map
        } catch (_: Throwable) {
            // 兼容旧格式: k:v,k:v
            val map = mutableMapOf<String, String>()
            text.removePrefix("{").removeSuffix("}")
                .split(",")
                .forEach { part ->
                    val kv = part.split(":", limit = 2)
                    if (kv.size == 2) {
                        map[kv[0].replace("\"", "").trim()] = kv[1].replace("\"", "").trim()
                    }
                }
            return map
        }
    }

    private fun encodeUrl(raw: String): String {
        return try {
            java.net.URI(raw).toASCIIString()
        } catch (_: Throwable) {
            raw.replace(" ", "%20").replace(Regex("[^\\x00-\\x7F]+")) {
                URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")
            }
        }
    }

    suspend fun parse(urlStr: String): Playlist? = withContext(Dispatchers.IO) {
        lastError = ""
        if (urlStr.isBlank()) {
            lastError = "URL为空"
            return@withContext null
        }

        try {
            val headerSplit = urlStr.split("#headers#", limit = 2)
            val targetUrl = headerSplit[0].trim()
            val headers = parseHeaders(headerSplit.getOrNull(1))

            if (targetUrl.isEmpty()) {
                lastError = "URL为空"
                return@withContext null
            }

            val lines = if (targetUrl.startsWith("file://", ignoreCase = true) ||
                targetUrl.startsWith("/") ||
                targetUrl.startsWith("content://", ignoreCase = true)
            ) {
                readLocal(targetUrl)
            } else {
                readRemote(targetUrl, headers)
            } ?: return@withContext null

            var finalLines = lines
            if (finalLines.isNotEmpty()) {
                val fullText = finalLines.joinToString("\n").trim()
                val isPlainText = fullText.startsWith("#EXTM3U") || fullText.contains(",#genre#") ||
                    fullText.contains("，#genre#") || fullText.contains("#EXTINF")
                if (!isPlainText) {
                    val decryptedText = AESCrypt.decrypt(fullText)
                    if (!decryptedText.isNullOrEmpty() &&
                        (decryptedText.startsWith("#EXTM3U") || decryptedText.contains(",#genre#") ||
                            decryptedText.contains("#EXTINF"))
                    ) {
                        finalLines = decryptedText.lines()
                    }
                }
            }

            return@withContext parseLines(finalLines)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        }
        return@withContext null
    }

    private fun readLocal(targetUrl: String): List<String>? {
        val path = if (targetUrl.startsWith("file://", ignoreCase = true)) {
            try {
                java.net.URI(targetUrl).path ?: targetUrl.removePrefix("file://")
            } catch (_: Throwable) {
                targetUrl.removePrefix("file://")
            }
        } else {
            targetUrl
        }
        val file = File(path)
        if (!file.isFile) {
            lastError = "本地文件不存在: $path"
            return null
        }
        return try {
            decodeBytes(file.readBytes()).lines()
        } catch (t: Throwable) {
            lastError = "读取本地文件失败: ${t.message}"
            null
        }
    }

    private fun readRemote(targetUrl: String, headers: Map<String, String>): List<String>? {
        val resolvedUrl = com.iptv.tvplayer.proxy.ProxyManager.resolveUrl(targetUrl)
        val encodedUrl = encodeUrl(resolvedUrl)
        val connection = try {
            URL(encodedUrl).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            lastError = "URL格式错误: ${t.message}"
            return null
        }

        try {
            if (connection is javax.net.ssl.HttpsURLConnection) {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                })
                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, trustAll, java.security.SecureRandom())
                connection.sslSocketFactory = sc.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            val timeoutMs = (com.iptv.tvplayer.data.SettingsManager.timeoutSeconds.coerceIn(3, 60)) * 1000
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", com.iptv.tvplayer.data.SettingsManager.userAgent)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            if (connection.responseCode !in 200..299) {
                lastError = "HTTP错误: ${connection.responseCode} ${connection.responseMessage}"
                return null
            }

            val bytes = BufferedInputStream(connection.inputStream).use { raw ->
                raw.mark(4)
                val magic = ByteArray(4)
                val read = raw.read(magic)
                raw.reset()
                val isGzip = connection.contentEncoding?.lowercase(Locale.US)?.contains("gzip") == true ||
                    (read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte())
                if (isGzip) {
                    GZIPInputStream(raw).use { it.readBytes() }
                } else {
                    raw.readBytes()
                }
            }
            return decodeBytes(bytes).lines()
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            return null
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseLines(rawLines: List<String>): Playlist {
        val lines = rawLines.map { it.removePrefix("\uFEFF") }
        val isM3u = lines.any {
            val t = it.trimStart()
            t.startsWith("#EXTM3U", ignoreCase = true) || t.startsWith("#EXTINF", ignoreCase = true)
        }
        return if (isM3u) parseM3u(lines) else parseTxt(lines)
    }

    private class ChannelAccumulator {
        val categories = mutableListOf<String>()
        val channelsMap = LinkedHashMap<String, MutableList<Channel>>()
        private val categorySet = HashSet<String>()
        private val index = HashMap<String, HashMap<String, Int>>()

        fun ensureCategory(category: String) {
            if (categorySet.add(category)) {
                categories.add(category)
                channelsMap[category] = mutableListOf()
            }
        }

        fun add(
            category: String,
            name: String,
            urls: List<String>,
            logo: String? = null,
            drmType: String? = null,
            drmKey: String? = null,
            catchup: String? = null,
            catchupSource: String? = null,
            catchupDays: Int = 0
        ) {
            if (name.isBlank() || urls.isEmpty()) return
            ensureCategory(category)
            val list = channelsMap[category] ?: return
            val idxMap = index.getOrPut(category) { HashMap() }
            val existingIndex = idxMap[name]

            if (existingIndex != null && existingIndex in list.indices) {
                val old = list[existingIndex]
                list[existingIndex] = old.copy(
                    urls = (old.urls + urls).distinct(),
                    logo = old.logo ?: logo,
                    drmType = old.drmType ?: drmType,
                    drmKey = old.drmKey ?: drmKey,
                    catchup = old.catchup ?: catchup,
                    catchupSource = old.catchupSource ?: catchupSource,
                    catchupDays = if (old.catchupDays > 0) old.catchupDays else catchupDays
                )
            } else {
                idxMap[name] = list.size
                list.add(
                    Channel(
                        name = name,
                        urls = urls.distinct(),
                        logo = logo,
                        drmType = drmType,
                        drmKey = drmKey,
                        catchup = catchup,
                        catchupSource = catchupSource,
                        catchupDays = catchupDays
                    )
                )
            }
        }

        fun build(): Playlist = Playlist(categories.toList(), channelsMap)
    }

    private fun splitUrls(raw: String): List<String> {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isEmpty()) return emptyList()
        if (value.contains("#headers#", ignoreCase = true)) return listOf(value)

        // TVBox 常见写法: 频道,http://a.m3u8#http://b.m3u8
        if (value.startsWith("http", ignoreCase = true) && value.contains("#")) {
            val pieces = value.split("#").map { it.trim() }.filter { it.isNotEmpty() }
            if (pieces.size > 1 && pieces.drop(1).all { urlSchemeRegex.containsMatchIn(it) }) {
                return pieces
            }
        }
        return listOf(value)
    }

    private fun addKodiProp(target: MutableMap<String, String>, line: String) {
        val body = line.removePrefix("#KODIPROP:").removePrefix("#kodiprop:")
        val parts = body.split("=", limit = 2)
        if (parts.size == 2) {
            target[parts[0].trim()] = parts[1].trim()
        }
    }

    private fun parseTxt(lines: List<String>): Playlist {
        val acc = ChannelAccumulator()
        var currentCategory = "未分类"
        var pendingName: String? = null
        val kodiProps = mutableMapOf<String, String>()

        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue

            if (text.startsWith("#")) {
                if (text.startsWith("#KODIPROP:", ignoreCase = true)) {
                    addKodiProp(kodiProps, text)
                }
                continue
            }

            val genreIndex = text.indexOf("#genre#", ignoreCase = true)
            if (genreIndex >= 0) {
                val name = text.substring(0, genreIndex).trim().trimEnd(',', '，').trim()
                currentCategory = name.ifBlank { "未分类" }
                acc.ensureCategory(currentCategory)
                pendingName = null
                kodiProps.clear()
                continue
            }

            // KODIPROP 之后单独一行的 URL
            if (pendingName != null) {
                val comma = text.indexOf(',')
                // 只有“逗号前不是 URL”才视为新频道; 否则把整行当作上一行的 URL
                // (URL 查询参数里也可能带逗号)
                val looksLikeNewChannel = comma > 0 &&
                    !urlSchemeRegex.containsMatchIn(text.substring(0, comma).trim())
                if (!looksLikeNewChannel) {
                    val drmType = kodiProps["inputstream.adaptive.license_type"]
                    val drmKey = kodiProps["inputstream.adaptive.license_key"]
                    acc.add(
                        currentCategory,
                        pendingName,
                        splitUrls(text),
                        drmType = drmType,
                        drmKey = drmKey
                    )
                    pendingName = null
                    kodiProps.clear()
                    continue
                }
                pendingName = null
                kodiProps.clear()
            }

            val comma = text.indexOf(',')
            if (comma <= 0) continue
            val name = text.substring(0, comma).trim()
            if (name.isEmpty()) continue
            val rest = text.substring(comma + 1).trim()

            if (rest.startsWith("#KODIPROP:", ignoreCase = true)) {
                pendingName = name
                addKodiProp(kodiProps, rest)
                continue
            }
            if (rest.isEmpty()) {
                pendingName = name
                continue
            }

            val drmType = kodiProps["inputstream.adaptive.license_type"]
            val drmKey = kodiProps["inputstream.adaptive.license_key"]
            acc.add(
                currentCategory,
                name,
                splitUrls(rest),
                drmType = drmType,
                drmKey = drmKey
            )
            kodiProps.clear()
        }

        return acc.build()
    }

    private fun attr(text: String, name: String): String? {
        val regex = Regex(
            "(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(text) ?: return null
        val value = match.groupValues[2].ifEmpty {
            match.groupValues[3].ifEmpty { match.groupValues[4] }
        }
        return value.trim().ifEmpty { null }
    }

    /** 取 #EXTINF 行中引号外的第一个逗号后的频道名 (频道名本身可含逗号) */
    private fun extInfName(text: String): String {
        var inQuote = false
        var quoteChar = ' '
        for (i in text.indices) {
            val c = text[i]
            if (inQuote) {
                if (c == quoteChar) inQuote = false
            } else {
                when (c) {
                    '"', '\'' -> {
                        inQuote = true
                        quoteChar = c
                    }
                    ',' -> return text.substring(i + 1).trim()
                }
            }
        }
        return ""
    }

    private fun parseM3u(lines: List<String>): Playlist {
        val acc = ChannelAccumulator()
        var currentCategory = "未分类"
        var currentName = ""
        var currentLogo: String? = null
        var currentCatchup: String? = null
        var currentCatchupSource: String? = null
        var currentCatchupDays = 0
        val kodiProps = mutableMapOf<String, String>()

        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue

            when {
                text.startsWith("#EXTM3U", ignoreCase = true) -> {
                    // 可含 url-tvg / x-tvg-url, 目前不需要
                }

                text.startsWith("#EXTINF", ignoreCase = true) -> {
                    kodiProps.clear()
                    currentLogo = null
                    currentCatchup = null
                    currentCatchupSource = null
                    currentCatchupDays = 0

                    val group = attr(text, "group-title") ?: attr(text, "group")
                    if (!group.isNullOrBlank()) currentCategory = group

                    currentLogo = attr(text, "tvg-logo") ?: attr(text, "logo")
                    currentCatchup = attr(text, "catchup") ?: attr(text, "catchup-type")
                    currentCatchupSource = attr(text, "catchup-source")
                    currentCatchupDays = attr(text, "catchup-days")?.toIntOrNull()
                        ?: attr(text, "timeshift")?.toIntOrNull()
                        ?: 0

                    currentName = extInfName(text).ifEmpty { attr(text, "tvg-name") ?: "" }
                }

                text.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val group = text.substringAfter(':').trim()
                    if (group.isNotEmpty()) currentCategory = group
                }

                text.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    addKodiProp(kodiProps, text)
                }

                text.startsWith("#") -> {
                    // #EXTVLCOPT / #EXT-X-... 等忽略
                }

                else -> {
                    if (currentName.isNotEmpty()) {
                        val drmType = kodiProps["inputstream.adaptive.license_type"]
                        val drmKey = kodiProps["inputstream.adaptive.license_key"]
                        acc.add(
                            currentCategory,
                            currentName,
                            splitUrls(text),
                            logo = currentLogo,
                            drmType = drmType,
                            drmKey = drmKey,
                            catchup = currentCatchup,
                            catchupSource = currentCatchupSource,
                            catchupDays = currentCatchupDays
                        )
                    }
                }
            }
        }

        return acc.build()
    }
}
