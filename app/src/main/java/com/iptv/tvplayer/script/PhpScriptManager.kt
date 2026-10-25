package com.iptv.tvplayer.script

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 解析脚本支持 (PHP/Python)
 *
 * 频道列表写法:  cctv1,local://ysp(1).php?id=cctv1
 *                dfws,local://dfws.py?id=dfws
 * 脚本文件放在设备存储根目录:
 *   摸鱼TV/php/<脚本名>.php   摸鱼TV/py/<脚本名>.py   (js 脚本在 摸鱼TV/js)
 *
 * 执行方式分三层:
 *  1. 内容指纹命中的脚本 (ysp/wxty) → Kotlin 移植实现 (YspParser/WxtyParser, 最快最稳)
 *  2. 其他 .py 脚本 → Chaquopy 内嵌 CPython (PyScriptManager)
 *  3. 其他 .php 脚本 → php -S 子进程桥接 (PhpRuntimeBridge, 内置二进制或 摸鱼TV/bin/php)
 *
 * 解析结果 (m3u8 内容) 通过 LocalServer 的 /phpout/ 路由回流给播放器。
 */
object PhpScriptManager {
    private const val TAG = "PhpScriptManager"
    const val LOCAL_BASE = "http://127.0.0.1:8080/phpout"

    sealed class PhpResult {
        data class M3u8(val content: String) : PhpResult()
        data class Redirect(val url: String) : PhpResult()
        data class Error(val status: Int, val message: String) : PhpResult()
        /** CatVod 爬虫协议接口的 JSON 结果 */
        data class Json(val content: String) : PhpResult()
        /** CatVod 爬虫 localProxy 的转发结果 */
        data class Proxy(val status: Int, val mime: String, val body: String, val headers: Map<String, String>) : PhpResult()
    }

    /** 各脚本最近一次协议调用的 ext (供 localProxy 重放 init(extend) 还原配置) */
    private val lastExt = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val scriptRegex =
        Regex("^(?:local|file)://(.+\\.(?:php|py))(?:\\?(.*))?$", RegexOption.IGNORE_CASE)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Python 运行时首次初始化较重, 放到后台线程
        PyScriptManager.markBegun()   // 先同步标记: 冷启动即播时按"初始化中"等待而非直接报未初始化
        Thread {
            try {
                PyScriptManager.init(context)
            } catch (t: Throwable) {
                Log.e(TAG, "PyScriptManager init failed", t)
            }
        }.apply { isDaemon = true; start() }
    }

    fun matches(rawUrl: String): Boolean = scriptRegex.find(rawUrl.trim()) != null

    /** 把 local://xxx.php?query (或 .py) 转成 LocalServer 上的 m3u8 地址; 不支持则返回 null */
    fun resolve(rawUrl: String): String? {
        val m = scriptRegex.find(rawUrl.trim()) ?: return null
        val scriptRef = m.groupValues[1]
        val query = m.groupValues[2]
        val file = locateScript(scriptRef)
        if (file == null) {
            Log.e(TAG, "script not found: $scriptRef")
            return null
        }

        // CatVod 爬虫协议 (?api=): 先执行一次, 直接把最终可播地址交给播放器。
        // 播放器若按 /phpout/<名>.m3u8 路由地址准备播放会被强制判定为 HLS 类型,
        // 跳转到直链 mp4/ts 时会因"不是 #EXTM3U"解析失败; 预解析后最终地址与
        // 普通 http 频道行走完全相同的类型判定, 直链上的代理注释也照常生效。
        if (file.name.endsWith(".py", ignoreCase = true) && query.contains("api=")) {
            val params = HashMap<String, String>()
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) params[percentDecode(kv[0])] = percentDecode(kv[1])
            }
            if (params.containsKey("api")) {
                val r = execute(file.name, params)
                if (r is PhpResult.Redirect) return r.url
                if (r !is PhpResult.M3u8) {
                    Log.e(TAG, "catvod api resolve failed: $rawUrl -> $r")
                    return null
                }
                // M3u8 结果仍走 phpout 回流 (少见, 留作兼容)
            }
        }

        val encoded = java.net.URLEncoder.encode(file.name, "UTF-8")
        return if (query.isNotEmpty()) "$LOCAL_BASE/$encoded.m3u8?$query"
        else "$LOCAL_BASE/$encoded.m3u8"
    }

    /** 仅解 %XX (与 NanoHTTPD decodePercent 一致, 不把 + 当空格) */
    private fun percentDecode(s: String): String {
        if (!s.contains("%")) return s
        return try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    /** LocalServer 路由入口: 执行脚本并返回 m3u8 / 302 / 错误 */
    fun execute(scriptName: String, params: Map<String, String>): PhpResult {
        val ctx = appContext
            ?: return PhpResult.Error(502, "脚本管理器未初始化")
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "execute >> $scriptName $params")
        if (scriptName.endsWith(".php", ignoreCase = true)) {
            // 预热指纹代理 WebView (异步): 页面加载与脚本 getParam/WS 前置链路并行,
            // 缩短首个 gdtv-api 请求等待代理就绪的时间
            try { ChromeFetchProxy.ensureInit(ctx) } catch (_: Throwable) {}
        }
        return try {
            val file = locateScript(scriptName)
                ?: return PhpResult.Error(404, "脚本不存在: $scriptName")
            val query = params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            if (file.name.endsWith(".py", ignoreCase = true)) {
                val api = params["api"]
                if (api != null) {
                    // CatVod 爬虫协议模式: local://xxx.py?api=playerContent&args=<urlencoded JSON>[&ext=<站点配置>]
                    val ext = params["ext"] ?: ""
                    lastExt[file.name] = ext
                    val args = params["args"] ?: "[]"
                    val r = PyScriptManager.callApi(file, ext, api, args)
                    // playerContent 便捷直链: parse==0 且 url 为可播协议 → 302, 爬虫可直接当频道播放
                    // (rtsp/rtmp 也放行: 频道行经预解析后直接把地址交给播放器, rtsp 由既有的自动切 MPV 处理)
                    if (r is PhpResult.Json && api.equals("playerContent", ignoreCase = true)) {
                        try {
                            val o = org.json.JSONObject(r.content)
                            val url = o.optString("url", "")
                            val u = url.lowercase(java.util.Locale.US)
                            if (o.optInt("parse", 0) == 0 &&
                                (u.startsWith("http://") || u.startsWith("https://") ||
                                    u.startsWith("rtsp://") || u.startsWith("rtmp://"))) {
                                Log.i(TAG, "playerContent 直链: $url")
                                return PhpResult.Redirect(url)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "playerContent 结果解析失败: ${r.content.take(200)}")
                        }
                    }
                    Log.d(TAG, "execute << $scriptName#$api in ${System.currentTimeMillis() - t0}ms: ${r}")
                    return r
                }
                val r = PyScriptManager.execute(file, query)
                Log.d(TAG, "execute << $scriptName in ${System.currentTimeMillis() - t0}ms: $r")
                return r
            }
            val content = file.readText()
            val result = when (fingerprint(content)) {
                "ysp" -> if (yspKotlinRouted(content, params)) {
                    YspParser.parse(content, params)
                } else {
                    // 多源 ysp 指纹脚本 (央卫地方.php 等) 的列表/地方台/咪咕请求
                    PhpRuntimeBridge.execute(ctx, file, query)
                }
                "wxty" -> WxtyParser.parse(content, params)
                else -> PhpRuntimeBridge.execute(ctx, file, query)
            }
            Log.d(TAG, "execute << $scriptName in ${System.currentTimeMillis() - t0}ms: $result")
            result
        } catch (t: Throwable) {
            Log.e(TAG, "execute script failed: $scriptName", t)
            PhpResult.Error(502, "脚本执行失败: ${t.message}")
        }
    }

    /**
     * ysp 指纹 (bkliveinfo / CKeyManager) 是否按 Kotlin 移植执行:
     * 仅当请求是"纯央视频道直连" (?id=<YSP频道>) 才走 YspParser;
     * ?list / ?source / ?pid / 无 id / 非 YSP 的 id 全部交回完整 PHP 运行时 ——
     * Kotlin 移植只实现 YSP 一路, 其余 (综合列表、地方台、咪咕) 由脚本自身逻辑处理。
     */
    private fun yspKotlinRouted(content: String, params: Map<String, String>): Boolean {
        if (params.containsKey("list") || params.containsKey("source") || params.containsKey("pid")) return false
        val id = params["id"] ?: return false
        return YspParser.knowsYspChannel(content, id)
    }

    private fun fingerprint(content: String): String? = when {
        content.contains("bkliveinfo.ysp.cctv.cn") || content.contains("CKeyManager") -> "ysp"
        content.contains("kapi.kankanews.com") -> "wxty"
        else -> null
    }

    /**
     * CatVod 爬虫 localProxy 转发: LocalServer /pyproxy/<脚本名>?p=<JSON> 入口。
     * 复用该脚本最近一次协议调用的 ext 以还原 init(extend) 配置。
     */
    fun pyProxy(scriptName: String, paramJson: String): PhpResult {
        val file = locateScript(scriptName)
            ?: return PhpResult.Error(404, "脚本不存在: $scriptName")
        if (!file.name.endsWith(".py", ignoreCase = true)) {
            return PhpResult.Error(400, "localProxy 仅支持 Python 脚本")
        }
        val param = paramJson.trim().ifEmpty { "{}" }
        val r = PyScriptManager.callApi(file, lastExt[file.name] ?: "", "localProxy", "[$param]")
        if (r !is PhpResult.Json) return r
        return try {
            val arr = org.json.JSONArray(r.content)
            val status = arr.optInt(0, 200)
            val mime = arr.optString(1, "text/plain")
            val body = arr.optString(2, "")
            val headers = HashMap<String, String>()
            val hObj = arr.optJSONObject(3)
            if (hObj != null) {
                val it = hObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    headers[k] = hObj.optString(k)
                }
            }
            PhpResult.Proxy(status, mime, body, headers)
        } catch (t: Throwable) {
            PhpResult.Error(502, "localProxy 返回格式错误: ${r.content.take(200)}")
        }
    }

    private fun locateScript(scriptRef: String): File? {
        val isFileUri = scriptRef.startsWith("file://", ignoreCase = true)
        val name = when {
            scriptRef.startsWith("local://", ignoreCase = true) -> scriptRef.substring(8)
            isFileUri -> scriptRef.substring(7)
            else -> scriptRef
        }.trim()

        if (name.isEmpty()) return null
        // 防止 local://../../xxx 读取任意文件; 允许子目录, 但拒绝 .. 与绝对路径
        if (!isFileUri && (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":"))) {
            Log.w(TAG, "Rejected suspicious script path: $scriptRef")
            return null
        }
        if (!name.endsWith(".php", ignoreCase = true) && !name.endsWith(".py", ignoreCase = true)) {
            return null
        }

        // file:// 绝对路径
        if (isFileUri) {
            val abs = File(name)
            return if (abs.isFile) abs else null
        }
        // 搜索目录: 摸鱼TV/php, 摸鱼TV/py (兼容旧的 摸鱼TV/PHP), 应用私有目录
        val dirNames = arrayOf("php", "py", "PHP")
        val candidates = ArrayList<File?>()
        try {
            val base = android.os.Environment.getExternalStorageDirectory()
            for (d in dirNames) candidates.add(File(File(base, "摸鱼TV"), d).let { File(it, name) })
        } catch (e: Exception) {
        }
        appContext?.let { ctx ->
            for (d in dirNames) candidates.add(File(File(ctx.getExternalFilesDir(null), d), name))
        }
        for (d in dirNames) candidates.add(File(File(appContext?.cacheDir, d), name))
        return candidates.firstOrNull { it != null && it.isFile }
    }
}
