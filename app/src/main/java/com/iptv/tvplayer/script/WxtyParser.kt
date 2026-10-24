package com.iptv.tvplayer.script

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * wxty(2).php 的 Kotlin 移植 — 看看新闻(文广 kankan) 直播解析
 * RSA 公钥解密 + kankan API 签名 + 播放列表压平; 失败时 302 到兜底源。
 */
object WxtyParser {
    private const val TAG = "WxtyParser"
    private const val KANKAN_API_BASE = "https://kapi.kankanews.com"
    private const val KANKAN_API_VERSION = "2.42.23"
    private const val KANKAN_API_SECRET = "28c8edde3d61a0411511d3b1866f0636"
    private const val KANKAN_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 SE 2.X MetaSr 1.0"
    private const val FALLBACK_LIVE_URL = "https://cdn.jsdelivr.net/gh/jkwu5472/first/media.m3u8"
    private const val LIVE_URL_CACHE_TTL_MS = 600_000L
    private const val LIVE_URL_MIN_LEFT_S = 90L
    private const val DONOR_SCAN_DAYS = 7

    private val DEFAULT_CHANNEL_MAP = linkedMapOf(
        "dfws" to 1, "shxwzh" to 2, "shds" to 4, "dycj" to 5,
        "hhxd" to 9, "wxty" to 10, "mdy" to 11, "jsrw" to 12
    )

    private val client: OkHttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ssl = SSLContext.getInstance("SSL").apply { init(null, trustAll, java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private class LiveCache(val url: String, val timeMs: Long, val exp: Long)
    private val liveUrlCache = ConcurrentHashMap<Int, LiveCache>()

    @Volatile
    private var stableUuidValue: String? = null

    // ================= 主流程 =================
    fun parse(content: String, params: Map<String, String>): PhpScriptManager.PhpResult {
        val id = params["id"] ?: "wxty"
        val channelId = parseChannelMap(content)[id]
            ?: return PhpScriptManager.PhpResult.Error(400, "无效频道ID")
        val forceRefresh = params["refresh"] != null && params["refresh"] != "0"

        var cacheHit = false
        var liveUrl = getLiveUrl(channelId, forceRefresh)?.also { cacheHit = it.second }?.first
        if (liveUrl == null) {
            return PhpScriptManager.PhpResult.Redirect(FALLBACK_LIVE_URL)
        }

        var finalM3u8 = getFlatM3u8(liveUrl, 0)
        if (finalM3u8.isEmpty() && cacheHit) {
            liveUrlCache.remove(channelId)
            liveUrl = getLiveUrl(channelId, true)?.first
            if (liveUrl != null) finalM3u8 = getFlatM3u8(liveUrl, 0)
        }
        if (finalM3u8.isEmpty()) {
            return PhpScriptManager.PhpResult.Redirect(FALLBACK_LIVE_URL)
        }
        return PhpScriptManager.PhpResult.M3u8(finalM3u8)
    }

    /** 从脚本源码解析 $channelMap = ['dfws' => 1, ...]; 解析失败用内置表 */
    fun parseChannelMap(content: String): Map<String, Int> {
        val block = Regex("\\\$channelMap\\s*=\\s*\\[(.*?)\\];", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(content)?.groupValues?.get(1)
        if (block != null) {
            val map = LinkedHashMap<String, Int>()
            for (m in Regex("'([A-Za-z0-9_]+)'\\s*=>\\s*(\\d+)").findAll(block)) {
                map[m.groupValues[1]] = m.groupValues[2].toInt()
            }
            if (map.isNotEmpty()) return map
        }
        return DEFAULT_CHANNEL_MAP
    }

    // ================= 播放地址 =================
    /** 返回 (url, cacheHit) */
    private fun getLiveUrl(channelId: Int, forceRefresh: Boolean): Pair<String, Boolean>? {
        if (!forceRefresh) {
            val cached = readCachedLiveUrl(channelId)
            if (cached != null) return Pair(cached, true)
        }
        val found = fetchPlayAddress(channelId) ?: run {
            Log.w(TAG, "address-failed: 未取到可用播放地址 (channel=$channelId)")
            return null
        }
        val plain = rsaDecrypt(found.addr) ?: run {
            Log.w(TAG, "decrypt-failed: RSA 解密失败 (source=${found.source})")
            return null
        }
        val liveUrl = normalizeLiveUrl(plain)
        if (liveUrl.isEmpty()) {
            Log.w(TAG, "bad-url: 解密结果不是合法 URL")
            return null
        }
        writeCachedLiveUrl(channelId, liveUrl)
        return Pair(liveUrl, false)
    }

    private fun readCachedLiveUrl(channelId: Int): String? {
        val c = liveUrlCache[channelId] ?: return null
        val now = System.currentTimeMillis()
        if (now - c.timeMs >= LIVE_URL_CACHE_TTL_MS) return null
        if (!c.url.startsWith("https://", ignoreCase = true)) return null
        if (c.exp > 0 && c.exp - now / 1000 < LIVE_URL_MIN_LEFT_S) return null
        return c.url
    }

    private fun writeCachedLiveUrl(channelId: Int, url: String) {
        var exp = urlExpiry(url)
        if (exp <= 0) exp = System.currentTimeMillis() / 1000 + 300
        liveUrlCache[channelId] = LiveCache(url, System.currentTimeMillis(), exp)
    }

    private class FoundAddr(val addr: String, val kind: String, val source: String, val programId: Any?, val programName: String?)

    private fun pickProgramAddress(pd: JSONObject?): Pair<String, String>? {
        val ci = pd?.optJSONObject("result")?.optJSONObject("channel_info") ?: return null
        val live = ci.optString("live_address") ?: ""
        val shift = ci.optString("shift_address") ?: ""
        if (live.isNotEmpty()) return Pair(live, "live")
        if (shift.isNotEmpty()) return Pair(shift, "shift")
        return null
    }

    private fun fetchPrograms(channelId: Int, date: String): List<JSONObject> {
        val res = kankanApiGet("/content/pc/tv/programs", mapOf("channel_id" to channelId.toString(), "date" to date))
        val arr = res?.optJSONObject("result")?.optJSONArray("programs") ?: return emptyList()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        return list
    }

    private fun fetchProgramDetail(programId: Any?): JSONObject? {
        if (programId == null) return null
        return kankanApiGet("/content/pc/tv/program/detail", mapOf("channel_program_id" to programId.toString()))
    }

    private fun fetchPlayAddress(channelId: Int): FoundAddr? {
        val detail = kankanApiGet("/content/pc/tv/channel/detail", mapOf("channel_id" to channelId.toString()))
        val chanLive = detail?.optJSONObject("result")?.optString("live_address") ?: ""
        if (chanLive.isNotEmpty()) return FoundAddr(chanLive, "live", "channel", null, null)

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val list = fetchPrograms(channelId, today)
        val now = System.currentTimeMillis() / 1000
        val current = list.firstOrNull {
            val s = it.optLong("start_time")
            val e = it.optLong("end_time")
            s <= now && now < e
        }
        if (current != null) {
            val pick = pickProgramAddress(fetchProgramDetail(current.opt("id")))
            if (pick != null) {
                return FoundAddr(pick.first, pick.second, "current-program", current.opt("id"), current.optString("name"))
            }
        }

        var fallbackShift: FoundAddr? = null
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (off in 0 until DONOR_SCAN_DAYS) {
            val date = df.format(java.util.Date(System.currentTimeMillis() - off * 86400_000L))
            val dayList = if (off == 0 && list.isNotEmpty()) list else fetchPrograms(channelId, date)
            if (dayList.isEmpty()) continue

            val candidates = dayList
                .filter {
                    it.optInt("can_review") == 1 || it.optInt("is_review") == 1
                }
                .sortedBy { if (it.optString("name").contains("体育")) 0 else 1 }

            for (p in candidates) {
                val pick = pickProgramAddress(fetchProgramDetail(p.opt("id")))
                if (pick != null && pick.second == "shift") {
                    return FoundAddr(pick.first, "shift", "donor", p.opt("id"), p.optString("name"))
                }
                if (pick != null && fallbackShift == null) {
                    fallbackShift = FoundAddr(pick.first, pick.second, "donor", p.opt("id"), p.optString("name"))
                }
            }
        }
        return fallbackShift
    }

    // ================= kankan API =================
    private fun kankanApiGet(path: String, params: Map<String, String>): JSONObject? {
        val t = System.currentTimeMillis() / 1000
        val nonce = randomNonce(8)
        val uuid = stableUuid()

        val all = TreeMap<String, String>()
        all.putAll(params)
        all["Api-Version"] = "v1"
        all["nonce"] = nonce
        all["platform"] = "pc"
        all["timestamp"] = t.toString()
        all["version"] = KANKAN_API_VERSION

        val signStr = all.entries.joinToString("") { it.key + "=" + it.value + "&" }
        val sign = md5(md5(signStr + KANKAN_API_SECRET))

        val query = params.entries.joinToString("&") { phpUrlEncode(it.key) + "=" + phpUrlEncode(it.value) }
        val url = if (query.isEmpty()) KANKAN_API_BASE + path else KANKAN_API_BASE + path + "?" + query

        return try {
            val req = Request.Builder()
                .url(url)
                .header("Api-Version", "v1")
                .header("Nonce", nonce)
                .header("M-Uuid", uuid)
                .header("Platform", "pc")
                .header("Version", KANKAN_API_VERSION)
                .header("Timestamp", t.toString())
                .header("Sign", sign)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://live.kankanews.com")
                .header("Referer", "https://live.kankanews.com/")
                .header("User-Agent", KANKAN_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                try { JSONObject(body) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "kankan api failed: $path", e)
            null
        }
    }

    // ================= RSA 公钥解密 (镜像 PHP gmp/openssl 双路径) =================
    private const val KANKAN_PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n" +
                "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDP5hzPUW5RFeE2xBT1ERB3hHZI\n" +
                "Votn/qatWhgc1eZof09qKjElFN6Nma461ZAwGpX4aezKP8Adh4WJj4u2O54xCXDt\n" +
                "wzKRqZO2oNZkuNmF2Va8kLgiEQAAcxYc8JgTN+uQQNpsep4n/o1sArTJooZIF17E\n" +
                "tSqSgXDcJ7yDj5rc7wIDAQAB\n" +
                "-----END PUBLIC KEY-----"

    private fun rsaDecrypt(str: String): String? {
        if (str.isEmpty()) return null
        val encData = try {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        if (encData.size < 128) return null

        val der = try {
            val body = KANKAN_PUBLIC_KEY_PEM
                .replace(Regex("-----[^-]+-----"), "")
                .replace(Regex("\\s+"), "")
            android.util.Base64.decode(body, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }

        val pub = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        } catch (e: Exception) {
            Log.e(TAG, "parse rsa public key failed", e)
            return null
        }
        val n = pub.modulus
        val e = pub.publicExponent

        val sb = StringBuilder()
        var i = 0
        while (i + 128 <= encData.size) {
            val chunk = BigInteger(1, encData.copyOfRange(i, i + 128))
            val m = chunk.modPow(e, n)
            val block = normalizeTo128(m)
            if (block != null && block[0] == 0.toByte() && block[1] == 1.toByte()) {
                var j = 2
                while (j < 128 && block[j] == 0xFF.toByte()) j++
                if (j < 128 && block[j] == 0.toByte()) {
                    sb.append(String(block, j + 1, 128 - (j + 1), Charsets.ISO_8859_1))
                }
            }
            i += 128
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun normalizeTo128(v: BigInteger): ByteArray? {
        if (v.signum() < 0) return null
        val bytes = v.toByteArray()
        return when {
            bytes.size == 128 -> bytes
            bytes.size > 128 -> bytes.copyOfRange(bytes.size - 128, bytes.size)
            else -> ByteArray(128).also { System.arraycopy(bytes, 0, it, 128 - bytes.size, bytes.size) }
        }
    }

    // ================= 播放列表压平 =================
    private fun fetchContent(url: String): String? {
        repeat(2) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", KANKAN_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Origin", "https://live.kankanews.com")
                    .header("Referer", "https://live.kankanews.com/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.code == 200 && !body.isNullOrEmpty()) return body
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetch content failed: $url", e)
            }
        }
        return null
    }

    private fun toAbsoluteUrl(base: String, relative: String): String {
        if (relative.isEmpty()) return base
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(relative)) return relative
        return try {
            val u = java.net.URI(base)
            val basePath = u.rawPath ?: "/"
            val baseDir = basePath.substringBeforeLast('/', "") + "/"
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return base
            "$scheme://$host$baseDir" + relative.removePrefix("/")
        } catch (e: Exception) {
            base
        }
    }

    private fun rewritePlaylistTagUris(line: String, base: String): String =
        Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).replace(line) { m ->
            "URI=\"" + toAbsoluteUrl(base, m.groupValues[1]) + "\""
        }

    private fun getFlatM3u8(url: String, depth: Int): String {
        if (depth > 5) return ""
        val content = fetchContent(url) ?: return ""
        val lines = content.split(Regex("\r\n|\n|\r"))
        val output = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    i++
                    if (i >= lines.size) break
                    val subUrl = lines[i].trim()
                    if (subUrl.isEmpty() || subUrl[0] == '#') { i++; continue }
                    val subContent = getFlatM3u8(toAbsoluteUrl(url, subUrl), depth + 1)
                    if (subContent.isNotEmpty()) return subContent
                }
                line.startsWith("#EXTINF:") -> {
                    output.add(line)
                    i++
                    if (i >= lines.size) break
                    val tsUrl = lines[i].trim()
                    output.add(if (tsUrl.isNotEmpty() && tsUrl[0] != '#') toAbsoluteUrl(url, tsUrl) else tsUrl)
                }
                line.isNotEmpty() -> output.add(rewritePlaylistTagUris(line, url))
            }
            i++
        }
        val joined = output.joinToString("\n")
        return if (joined.contains("#EXTINF")) joined else ""
    }

    // ================= URL 过期/清洗 =================
    private fun urlExpiry(url: String): Long {
        val query = try {
            java.net.URI(url).rawQuery
        } catch (e: Exception) {
            null
        } ?: return 0

        val q = LinkedHashMap<String, String>()
        for (kv in query.split("&")) {
            if (kv.isEmpty()) continue
            val parts = kv.split("=", limit = 2)
            q[parts[0]] = if (parts.size > 1) parts[1] else ""
        }

        val cands = ArrayList<Long>()
        for (k in listOf("volcTime", "txTime", "wsTime")) {
            val v = q[k] ?: continue
            if (v.isEmpty()) continue
            val value = if (v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && !v.all { it in '0'..'9' }) {
                try { java.lang.Long.parseLong(v, 16) } catch (e: Exception) { 0L }
            } else {
                phpLong(v)
            }
            cands.add(value)
        }
        q["token"]?.let { token ->
            val segs = token.split(".")
            if (segs.size >= 2) {
                try {
                    val payloadStr = String(android.util.Base64.decode(segs[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)
                    val exp = JSONObject(payloadStr).optLong("exp")
                    if (exp > 0) cands.add(exp)
                } catch (e: Exception) {
                }
            }
        }
        val valid = cands.filter { it > 1600000000L }
        return valid.minOrNull() ?: 0L
    }

    private fun normalizeLiveUrl(url: String): String {
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme ?: return url
            val host = u.host ?: return url
            val drop = setOf("start", "end", "starttime", "endtime")
            val keep = (u.rawQuery ?: "").split("&").filter {
                it.isNotEmpty() && it.substringBefore("=").lowercase() !in drop
            }
            val sb = StringBuilder("$scheme://$host")
            if (u.port != -1) sb.append(":").append(u.port)
            sb.append(u.rawPath ?: "")
            if (keep.isNotEmpty()) sb.append("?").append(keep.joinToString("&"))
            sb.toString()
        } catch (e: Exception) {
            url
        }
    }

    // ================= 杂项辅助 =================
    private fun randomNonce(len: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..len).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun stableUuid(): String {
        stableUuidValue?.let { return it }
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        val uuid = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP).take(21)
        stableUuidValue = uuid
        return uuid
    }

    private fun md5(str: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(str.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun phpLong(s: String): Long {
        var i = 0
        var sign = 1L
        if (s.startsWith("-")) { sign = -1; i = 1 } else if (s.startsWith("+")) i = 1
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) return 0
        return sign * (s.substring(start, i).toLongOrNull() ?: 0L)
    }

    private fun phpUrlEncode(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> for (b in ch.toString().toByteArray(Charsets.UTF_8)) sb.append("%%%02X".format(b))
            }
        }
        return sb.toString()
    }
}
