package com.iptv.tvplayer.script

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * ysp(1).php 的 Kotlin 移植 — 央视频 CCTV 直播解析 (CKey/TEA 加密 + bkliveinfo 接口)
 * 输出: m3u8 内容 (相对 .ts 路径重写为绝对地址), 回看(playseek)返回 302 跳转。
 */
object YspParser {
    private const val TAG = "YspParser"
    private const val API_BASE = "https://bkliveinfo.ysp.cctv.cn"

    // ---- TEA / CKeyManager 常量 (与 PHP 脚本一致) ----
    private const val DELTA = 0x9e3779b9L
    private const val ROUNDS = 16
    private const val SALT_LEN = 2
    private const val ZERO_LEN = 7
    private val TEA_CKEY = hexToBytes("59b2f7cf725ef43c34fdd7c123411ed3")
    private val GUARD_TEA_KEY = hexToBytes("110DBEC10C23E7D2E56A1CAD6914EF1B")
    private val XOR_KEY = intArrayOf(0x84, 0x2E, 0xED, 0x08, 0xF0, 0x66, 0xE6, 0xEA, 0x48, 0xB4, 0xCA, 0xA9, 0x91, 0xED, 0x6F, 0xF3)
    private val GUARD_XOR_KEY = intArrayOf(0xB3, 0xC9, 0x53, 0xA0, 0x69, 0x13, 0xAD, 0x4D)

    private const val CACHE_TTL_MS = 80_000L // PHP 侧 cookie 缓存 80 秒

    private data class Cached(val url: String, val time: Long)
    private val playUrlCache = ConcurrentHashMap<String, Cached>()

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
            .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class ChannelInfo(val cnlid: String, val livepid: String, val defn: String)

    // ================= 主流程 =================
    fun parse(content: String, params: Map<String, String>): PhpScriptManager.PhpResult {
        val id = params["id"] ?: "cctv1"
        val playseek = params["playseek"]
        val channels = parseChannels(content)
        val ch = channels[id]
            ?: return PhpScriptManager.PhpResult.Error(400, "未知频道: $id")

        val isLive = playseek.isNullOrEmpty()
        var playUrl: String? = null
        var m3u8: String? = null

        for (attempt in 1..2) {
            var needRefresh = true
            if (attempt == 1 && isLive) {
                val cached = playUrlCache[id]
                if (cached != null && System.currentTimeMillis() - cached.time <= CACHE_TTL_MS) {
                    playUrl = cached.url
                    needRefresh = false
                }
            }
            if (needRefresh) {
                playUrl = getPlayUrl(ch, playseek)
                    ?: return PhpScriptManager.PhpResult.Error(502, "获取播放地址失败")
                if (isLive) {
                    playUrlCache[id] = Cached(playUrl, System.currentTimeMillis())
                } else {
                    return PhpScriptManager.PhpResult.Redirect(playUrl)
                }
            }
            m3u8 = playUrl?.let { fetchM3u8(it) }
            if (m3u8 != null) break
            if (attempt == 1 && isLive && !needRefresh) playUrlCache.remove(id)
        }

        if (m3u8 == null || playUrl == null) {
            return PhpScriptManager.PhpResult.Error(502, "无法获取 M3U8 内容，请稍后重试")
        }
        val baseUrl = playUrl.substring(0, playUrl.lastIndexOf('/') + 1)
        // 与 PHP preg_replace("/(.*?.ts)/i", $baseUrl."$1", ...) 行为一致
        val rewritten = Regex("(.*?\\.ts)", RegexOption.IGNORE_CASE).replace(m3u8) { m ->
            if (m.value.startsWith("http")) m.value else baseUrl + m.value
        }
        return PhpScriptManager.PhpResult.M3u8(rewritten)
    }

    /** 从脚本源码解析频道表 $n = ['id' => ['cnlid','livepid','defn'], ...]; 兼容 array() 与 [] 两种写法 */
    fun parseChannels(content: String): Map<String, ChannelInfo> {
        val map = LinkedHashMap<String, ChannelInfo>()
        val re = Regex(
            "'([A-Za-z0-9_]+)'\\s*=>\\s*(?:array\\(|\\[)\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*(?:\\)|\\])"
        )
        for (m in re.findAll(content)) {
            val (id, cnlid, livepid, defn) = m.destructured
            map[id] = ChannelInfo(cnlid, livepid, defn)
        }
        return map
    }

    /**
     * 该 id 是否属于 YSP (央视频道) 表 — 供 PhpScriptManager 路由判定。
     * 央卫地方.php 这类多源脚本同样命中 ysp 指纹 (含 CKeyManager/bkliveinfo),
     * 但 Kotlin 移植只实现 YSP 一路; 列表 (?list)、指定源、咪咕、地方台必须交给
     * 完整 PHP 运行时。脚本里存在 $YSP_CHANNELS 时只在该数组块内查找,
     * 避免把 $BJ_CHANNELS/$AIQILU_CHANNELS 等同形三元组误认成 YSP 频道。
     */
    fun knowsYspChannel(content: String, id: String): Boolean {
        if (id.isEmpty()) return false
        val marker = content.indexOf("\$YSP_CHANNELS")
        if (marker >= 0) {
            var start = content.indexOf("array(", marker)
            if (start < 0) start = content.indexOf('[', marker)
            if (start >= 0) {
                val end = content.indexOf(");", start)
                val block = if (end > start) content.substring(start, end + 1) else content.substring(start)
                return parseChannels(block).containsKey(id)
            }
        }
        return parseChannels(content).containsKey(id)
    }

    // ================= 播放地址获取 =================
    private fun getPlayUrl(ch: ChannelInfo, playseek: String?): String? {
        val res = makeLiveRequest(ch, playseek)
        return if (res.isSuccess) res.playUrl else null
    }

    private class ApiRes(val isSuccess: Boolean, val playUrl: String?, val error: String?)

    private fun makeLiveRequest(ch: ChannelInfo, playseek: String?): ApiRes {
        val timestamp = System.currentTimeMillis() / 1000
        val guid = generateGuid()
        val ckey = generateCKey(ch.cnlid, timestamp, guid)
        val flowid = uuid4Upper() + "_4330403"

        var playbackTimestamp: Long? = null
        val isPlayback = !playseek.isNullOrEmpty()
        if (isPlayback) {
            playbackTimestamp = try {
                val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                fmt.parse(playseek!!.split("-")[0])!!.time / 1000
            } catch (e: Exception) {
                return ApiRes(false, null, "回看时间格式错误")
            }
        }

        val params = LinkedHashMap<String, String>()
        params["atime"] = "120"
        params["livepid"] = ch.livepid
        params["cnlid"] = ch.cnlid
        params["appVer"] = "V8.22.1035.3031"
        params["app_version"] = "300090"
        params["caplv"] = "1"
        params["cmd"] = "2"
        params["defn"] = ch.defn
        params["device"] = "iPhone"
        params["encryptVer"] = "4.2"
        params["getpreviewinfo"] = "0"
        params["hevclv"] = "33"
        params["lang"] = "zh-Hans_JP"
        params["livequeue"] = "0"
        params["logintype"] = "1"
        params["nettype"] = "1"
        params["newnettype"] = "1"
        params["newplatform"] = "4330403"
        params["platform"] = "4330403"
        params["sdtfrom"] = "v3021"
        params["spacode"] = "23"
        params["spaudio"] = "1"
        params["spdemuxer"] = "6"
        params["spdrm"] = "2"
        params["spdynamicrange"] = "7"
        params["spflv"] = "1"
        params["spflvaudio"] = "1"
        params["sphdrfps"] = "60"
        params["sphttps"] = "0"
        params["spvcode"] = android.util.Base64.encodeToString(spvcodeRaw(ch.defn).toByteArray(), android.util.Base64.NO_WRAP)
        params["spvideo"] = "4"
        params["stream"] = "1"
        params["system"] = "1"
        params["sysver"] = "ios18.2.1"
        params["uhd_flag"] = "4"
        params["cKey"] = ckey
        params["guid"] = guid
        params["fntick"] = timestamp.toString()
        params["flowid"] = flowid

        if (isPlayback) {
            params["playbacktime"] = playbackTimestamp!!.toString()
            var resp = sendHttpRequest(params)
            if (resp.isSuccess && !resp.playUrl.isNullOrEmpty()) return resp
            params.remove("playbacktime")
            resp = sendHttpRequest(params)
            if (resp.isSuccess && !resp.playUrl.isNullOrEmpty()) {
                var playurl = resp.playUrl!!
                val urlParts = playurl.split("/").toMutableList()
                if (urlParts.size >= 3) {
                    urlParts[2] = "tlivecloud-playback-cdn.ysp.cctv.cn/tcloud.cctv.com"
                    playurl = urlParts.joinToString("/")
                    playurl += (if (playurl.contains("?")) "&" else "?") + "starttime=$playbackTimestamp"
                }
                return ApiRes(true, playurl, resp.error)
            }
            return ApiRes(false, null, "获取回看地址失败")
        }
        params["playbacktime"] = "0"
        return sendHttpRequest(params)
    }

    private fun sendHttpRequest(params: Map<String, String>): ApiRes {
        val query = params.entries.joinToString("&") { phpUrlEncode(it.key) + "=" + phpUrlEncode(it.value) }
        return try {
            val req = Request.Builder()
                .url("$API_BASE?$query")
                .header("User-Agent", "qqlive")
                .header("Connection", "Keep-Alive")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = try { JSONObject(body) } catch (e: Exception) {
                    return ApiRes(false, null, "无效JSON")
                }
                val ok = json.optInt("iretcode", -1) == 0
                ApiRes(ok, json.optString("playurl").takeIf { ok && it.isNotEmpty() }, json.optString("errinfo", "ok"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ysp api request failed", e)
            ApiRes(false, null, "请求失败: ${e.message}")
        }
    }

    private fun fetchM3u8(playUrl: String): String? = try {
        val req = Request.Builder()
            .url(playUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Mobile/15E148 Safari/604.1"
            )
            .header("Referer", "https://tv.cctv.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrEmpty()) body else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetch m3u8 failed", e)
        null
    }

    // ================= CKeyManager 移植 =================
    private fun generateCKey(cnlid: String, timestamp: Long, guid: String): String {
        val randFlag = "_zj1A5Gh6QYcxWjIUGos2w=="
        val uuid4 = uuid4Lower()
        val ckGuardTime = generateCkGuardTime(timestamp, guid)
        val params = linkedMapOf<String, Any>(
            "Platform" to 4330403,
            "Timestamp" to timestamp,
            "Sdtfrom" to "dcgh",
            "vid" to cnlid,
            "guid" to guid,
            "appVer" to "V8.22.1035.3031",
            "randFlag" to randFlag,
            "uuid4" to uuid4,
            "ck_guard_time" to ckGuardTime
        )
        val buffer = buildPacket(params)
        return encryptDataToCKey(buffer)
    }

    private fun buildPacket(params: Map<String, Any>): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(hexToBytes("0000004200000004000004d2"))
        data.write(writeU32((params["Platform"] as Number).toLong()))
        data.write(writeU32(0))
        data.write(writeU32((params["Timestamp"] as Number).toLong()))
        for (key in listOf("Sdtfrom", "randFlag", "appVer", "vid", "guid")) {
            val s = params[key] as String
            data.write(writeU16(s.length))
            data.write(s.toByteArray(Charsets.ISO_8859_1))
        }
        data.write(writeU32(1))
        data.write(writeU32(1))
        val tail = listOf(
            "2622783A", "nil", params["uuid4"] as String, "nil", "v0.1.000",
            "com.cctv.yangshipin.app.iphone", "4330403", "ex_json_bus", "ex_json_vs",
            params["ck_guard_time"] as String
        )
        for (s in tail) {
            data.write(writeU16(s.length))
            data.write(s.toByteArray(Charsets.ISO_8859_1))
        }
        val dataBytes = data.toByteArray()
        val buffer = ByteArrayOutputStream()
        buffer.write(writeU16(dataBytes.size))
        buffer.write(dataBytes)
        val buf = buffer.toByteArray()
        val signature = calcSignature(buf)
        val out = ByteArrayOutputStream()
        out.write(buf, 0, 18)
        out.write(writeU32(signature))
        out.write(buf, 22, buf.size - 22)
        return out.toByteArray()
    }

    private fun encryptDataToCKey(data: ByteArray): String {
        val checksum = calcSignature(data)
        val encrypted = oiSymmetryEncrypt2(data, TEA_CKEY) + writeU32(checksum)
        val xored = ByteArray(encrypted.size) { i -> (encrypted[i].toInt() xor XOR_KEY[i and 15]).toByte() }
        val b64 = android.util.Base64.encodeToString(xored, android.util.Base64.NO_WRAP)
        return "--01" + b64.replace('+', '_').replace('/', '-').trimEnd('=')
    }

    private fun generateCkGuardTime(timestamp: Long, guid: String): String {
        val body = ByteArrayOutputStream()
        body.write(writeU32(timestamp))
        val parts = listOf(guardLastFive(guid), guardLastFive("null"), guardLastFive("null"), "-1")
        for (part in parts) {
            body.write(writeU16(part.length))
            body.write(part.toByteArray(Charsets.ISO_8859_1))
        }
        val bodyBytes = body.toByteArray()
        val plain = ByteArrayOutputStream()
        plain.write(writeU16(bodyBytes.size))
        plain.write(bodyBytes)
        val plainBytes = plain.toByteArray()
        val checksum = calcSignature(plainBytes)
        val encrypted = oiSymmetryEncrypt2(plainBytes, GUARD_TEA_KEY) + writeU32(checksum)
        val xored = ByteArray(encrypted.size) { i -> (encrypted[i].toInt() xor GUARD_XOR_KEY[i and 7]).toByte() }
        return xored.joinToString("") { "%02X".format(it) }
    }

    private fun guardLastFive(value: String): String = if (value.length >= 5) value.substring(value.length - 5) else ""

    private fun calcSignature(bytes: ByteArray): Long {
        var sig = 0L
        for (b in bytes) sig = (0x83L * sig + (b.toLong() and 0xFF)) and 0x7FFFFFFFL
        return sig
    }

    private fun spvcodeRaw(defn: String): String {
        val height = if (Regex("(4k|8k|hdr)", RegexOption.IGNORE_CASE).containsMatchIn(defn)) 2160 else 1080
        val s = listOf(30, 60).joinToString(",") { "$it:$height" }
        return "H($s|$s);2($s|$s)"
    }

    // ---- TEA ECB ----
    private fun teaEncryptECB(pInBuf: ByteArray, pKey: ByteArray): ByteArray {
        var buf = pInBuf
        if (buf.size < 8) buf = buf + ByteArray(8 - buf.size)
        var y = readU32(buf, 0)
        var z = readU32(buf, 4)
        val k = longArrayOf(readU32(pKey, 0), readU32(pKey, 4), readU32(pKey, 8), readU32(pKey, 12))
        var sum = 0L
        repeat(ROUNDS) {
            sum = (sum + DELTA) and 0xFFFFFFFFL
            y = (y + (((z shl 4) + k[0]) xor (z + sum) xor ((z shr 5) + k[1]))) and 0xFFFFFFFFL
            z = (z + (((y shl 4) + k[2]) xor (y + sum) xor ((y shr 5) + k[3]))) and 0xFFFFFFFFL
        }
        return writeU32(y) + writeU32(z)
    }

    // ---- OI 对称加密(填充 + 分组) ----
    private fun oiSymmetryEncrypt2(pInBuf: ByteArray, pKey: ByteArray): ByteArray {
        var nInBufLen = pInBuf.size
        var nPadSaltBodyZeroLen = nInBufLen + 1 + SALT_LEN + ZERO_LEN
        var nPadlen = nPadSaltBodyZeroLen % 8
        if (nPadlen != 0) nPadlen = 8 - nPadlen
        val out = ByteArrayOutputStream()
        val srcBuf = IntArray(8)
        srcBuf[0] = (Random.nextInt(256) and 0xF8) or nPadlen
        var srcI = 1
        while (nPadlen > 0) {
            srcBuf[srcI] = Random.nextInt(256)
            srcI++; nPadlen--
        }
        val ivPlain = IntArray(8)
        val ivCrypt = IntArray(8)

        fun emitBlock() {
            for (j in 0..7) srcBuf[j] = srcBuf[j] xor ivCrypt[j]
            val tempBytes = unpackBytes(teaEncryptECB(packC(srcBuf), pKey))
            for (j in 0..7) tempBytes[j] = tempBytes[j] xor ivPlain[j]
            for (j in 0..7) {
                ivPlain[j] = srcBuf[j]
                ivCrypt[j] = tempBytes[j]
            }
            out.write(packC(tempBytes))
            srcI = 0
        }

        var i = 0
        while (i < SALT_LEN) {
            if (srcI < 8) { srcBuf[srcI] = Random.nextInt(256); srcI++; i++ }
            if (srcI == 8) emitBlock()
        }
        var idx = 0
        while (nInBufLen > 0) {
            if (srcI < 8) { srcBuf[srcI] = pInBuf[idx].toInt() and 0xFF; idx++; srcI++; nInBufLen-- }
            if (srcI == 8) emitBlock()
        }
        i = 0
        while (i < ZERO_LEN) {
            if (srcI < 8) { srcBuf[srcI] = 0; srcI++; i++ }
            if (srcI == 8) emitBlock()
        }
        if (srcI > 0) {
            for (j in srcI until 8) srcBuf[j] = 0
            for (j in 0..7) srcBuf[j] = srcBuf[j] xor ivCrypt[j]
            val tempBytes = unpackBytes(teaEncryptECB(packC(srcBuf), pKey))
            for (j in 0..7) tempBytes[j] = tempBytes[j] xor ivPlain[j]
            out.write(packC(tempBytes))
        }
        return out.toByteArray()
    }

    // ---- 随机/格式化辅助 ----
    private fun generateGuid(): String {
        return rndHex(0xFFFFFFFFL).padStart(8, '0') + rndHex(0xFFFFL).padStart(4, '0') +
                rndHex(0xFFFFL).padStart(4, '0') + rndHex(0xFFFFL).padStart(4, '0') +
                rndHex(0xFFFFFFFFFFFFL).padStart(12, '0')
    }

    private fun rndHex(maxInclusive: Long): String =
        java.lang.Long.toHexString(Random.nextLong(maxInclusive + 1))

    private fun uuid4Lower(): String =
        "%04x%04x-%04x-%04x-%04x-%04x%04x%04x".format(
            Random.nextInt(0x10000), Random.nextInt(0x10000), Random.nextInt(0x10000),
            Random.nextInt(0x1000) or 0x4000, Random.nextInt(0x4000) or 0x8000,
            Random.nextInt(0x10000), Random.nextInt(0x10000), Random.nextInt(0x10000)
        )

    private fun uuid4Upper(): String =
        "%04X%04X-%04X-%04X-%04X-%04X%04X%04X".format(
            Random.nextInt(0x10000), Random.nextInt(0x10000), Random.nextInt(0x10000),
            Random.nextInt(0x1000) or 0x4000, Random.nextInt(0x4000) or 0x8000,
            Random.nextInt(0x10000), Random.nextInt(0x10000), Random.nextInt(0x10000)
        )

    // ---- 字节/编码辅助 ----
    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun writeU32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun writeU16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun packC(arr: IntArray): ByteArray = ByteArray(arr.size) { i -> (arr[i] and 0xFF).toByte() }

    private fun unpackBytes(b: ByteArray): IntArray = IntArray(b.size) { i -> b[i].toInt() and 0xFF }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(Regex("[^0-9a-fA-F]"), "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    /** 等价 PHP urlencode / http_build_query: 空格 -> '+', 非 A-Za-z0-9-_. 全部百分号编码 */
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
