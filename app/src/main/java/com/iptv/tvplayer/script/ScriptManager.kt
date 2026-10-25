package com.iptv.tvplayer.script

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@androidx.annotation.Keep
interface HttpBridge {
    @androidx.annotation.Keep fun fetch(url: String): String
    @androidx.annotation.Keep fun fetchWithHeaders(url: String, headersJson: String): String
    @androidx.annotation.Keep fun request(url: String, method: String, headersJson: String, body: String): String
    @androidx.annotation.Keep fun md5(value: String): String
    @androidx.annotation.Keep fun base64Encode(value: String): String
    @androidx.annotation.Keep fun base64Decode(value: String): String
    @androidx.annotation.Keep fun urlEncode(value: String): String
    @androidx.annotation.Keep fun urlDecode(value: String): String
    @androidx.annotation.Keep fun joinUrl(base: String, path: String): String
    @androidx.annotation.Keep fun log(message: String)
    @androidx.annotation.Keep fun sleep(millis: Long)
}

/**
 * 通用 JS 解析脚本执行器 (Rhino)。
 *
 * 兼容常见 TVBox / CatVod 脚本写法:
 *  - local://xxx.js            (脚本位于摸鱼TV/js)
 *  - local://xxx.js?arg=1      (参数作为第一个入参)
 *  - local://xxx.js/arg=1      (旧格式)
 *  - http(s)://host/xxx.js     (远程脚本, 自动缓存)
 *
 * 脚本入口依次尝试 getPlayUrl / getVideoUrl / getUrl / play / main,
 * 也支持脚本直接给全局 result 赋值。内置 req / md5 / base64 / urlencode /
 * joinUrl / console 等常用函数, 尽量贴近 TVBox JS 运行环境。
 */
object ScriptManager {
    private const val TAG = "ScriptManager"
    private const val JS_TIMEOUT_MS = 20_000L
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    private var appContext: Context? = null

    /** 每个执行线程的 JS 截止时间, 用于中断死循环脚本 */
    private val jsDeadline = ThreadLocal<Long>()

    private val jsContextFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: RhinoContext, instructionCount: Int) {
            val deadline = jsDeadline.get() ?: return
            if (System.currentTimeMillis() > deadline) {
                throw Error("JavaScript execution timeout")
            }
        }
    }

    /** local:// 或 file:// 形式; 兼容 .js 后直接跟 ? 或 / */
    private val jsRegex = Regex(
        "^((?:https?|local|file)://.+?\\.js)(?:[/?](.*))?$",
        RegexOption.IGNORE_CASE
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        PhpScriptManager.init(context)
    }

    suspend fun resolveUrl(rawUrl: String): String {
        return withContext(Dispatchers.IO) {
            val trimmed = rawUrl.trim()
            try {
                // PHP 解析脚本: local://ysp(1).php?id=cctv1 走 LocalServer /phpout/ 路由回流 m3u8
                if (PhpScriptManager.matches(trimmed)) {
                    return@withContext PhpScriptManager.resolve(trimmed) ?: ""
                }

                val jsMatch = jsRegex.find(trimmed)
                if (jsMatch != null) {
                    val scriptUrl = jsMatch.groupValues[1]
                    val scriptArgs = jsMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                    return@withContext executeJs(scriptUrl, scriptArgs)
                }

                return@withContext rawUrl
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to resolve script URL: $rawUrl", t)
                rawUrl
            }
        }
    }

    private fun md5(str: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(str.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 返回本地脚本文件; 远程脚本下载到缓存目录。
     * 下载失败时保留旧缓存, 不会用错误页覆盖可用脚本。
     */
    private fun downloadScript(url: String, extension: String): File? {
        // ---------- 本地脚本 ----------
        if (url.startsWith("local://", ignoreCase = true) || url.startsWith("file://", ignoreCase = true)) {
            val raw = if (url.startsWith("local://", ignoreCase = true)) {
                url.substring("local://".length)
            } else {
                url.substring("file://".length)
            }
            val path = raw.substringBefore('?').substringBefore('#').trim()
            if (path.isEmpty()) return null

            if (url.startsWith("file://", ignoreCase = true)) {
                return File(path)
            }

            // 拒绝目录穿越
            val normalized = path.replace('\\', '/')
            if (normalized.split('/').any { it == ".." }) {
                Log.w(TAG, "Rejected suspicious local script path: $path")
                return null
            }

            val candidates = ArrayList<File>()
            try {
                candidates.add(File(File(Environment.getExternalStorageDirectory(), "摸鱼TV/js"), normalized))
            } catch (_: Throwable) {
            }
            appContext?.let { ctx ->
                ctx.getExternalFilesDir(null)?.let { ext ->
                    candidates.add(File(File(ext, "js"), normalized))
                }
                candidates.add(File(File(ctx.filesDir, "js"), normalized))
            }
            return candidates.firstOrNull { it.isFile } ?: candidates.firstOrNull()
        }

        // ---------- 远程脚本 ----------
        val ctx = appContext ?: return null
        val cacheDir = File(ctx.filesDir, "scripts")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null

        val fileName = md5(url) + extension
        val scriptFile = File(cacheDir, fileName)

        val downloadUrl = if (url.contains("?")) {
            "$url&_cb=${System.currentTimeMillis()}"
        } else {
            "$url?_cb=${System.currentTimeMillis()}"
        }

        try {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Script download HTTP ${resp.code}: $url")
                    return@use
                }
                val content = resp.body?.string().orEmpty()
                if (content.isBlank() || content.length > 4_000_000) {
                    Log.w(TAG, "Script download returned empty/oversized body: $url")
                    return@use
                }
                // 简单校验, 避免用 404/登录页覆盖到缓存
                val looksLikeJs = content.contains("function") || content.contains("=>") ||
                    content.contains("var ") || content.contains("let ") || content.contains("const ")
                if (!looksLikeJs) {
                    Log.w(TAG, "Downloaded content does not look like JS: $url")
                    return@use
                }
                try {
                    scriptFile.writeText(content)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to write script cache", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download script, using cache if exists", t)
        }

        return scriptFile
    }

    private fun executeJs(scriptUrl: String, scriptArgs: String): String {
        val scriptFile = downloadScript(scriptUrl, ".js") ?: return ""
        if (!scriptFile.isFile) {
            Log.w(TAG, "Script not found: $scriptUrl")
            return ""
        }

        val scriptContent = try {
            scriptFile.readText()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read script: ${scriptFile.absolutePath}", t)
            return ""
        }

        var cx: RhinoContext? = null
        jsDeadline.set(System.currentTimeMillis() + JS_TIMEOUT_MS)
        try {
            cx = jsContextFactory.enterContext()
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setInstructionObserverThreshold(10_000)

            val scope = cx.initStandardObjects()

            val bridge = object : HttpBridge {
                override fun fetch(url: String): String = request(url, "GET", "{}", "")

                override fun fetchWithHeaders(url: String, headersJson: String): String =
                    request(url, "GET", headersJson, "")

                override fun request(url: String, method: String, headersJson: String, body: String): String {
                    return try {
                        val builder = Request.Builder().url(url)
                        var hasUa = false
                        try {
                            val headers = JSONObject(headersJson.ifBlank { "{}" })
                            val keys = headers.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                                builder.addHeader(k, headers.optString(k))
                            }
                        } catch (_: Throwable) {
                        }
                        if (!hasUa) builder.header("User-Agent", DEFAULT_UA)

                        val m = method.uppercase(Locale.US)
                        val requestBody = if (m == "POST" || m == "PUT" || m == "PATCH") {
                            body.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                        } else null
                        builder.method(m, requestBody)

                        client.newBuilder()
                            .callTimeout(20, TimeUnit.SECONDS)
                            .build()
                            .newCall(builder.build())
                            .execute()
                            .use { resp -> resp.body?.string().orEmpty() }
                    } catch (t: Throwable) {
                        Log.e(TAG, "JS request failed: $url", t)
                        ""
                    }
                }

                override fun md5(value: String): String = this@ScriptManager.md5(value)

                override fun base64Encode(value: String): String =
                    Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                override fun base64Decode(value: String): String {
                    return try {
                        val bytes = try {
                            Base64.decode(value, Base64.DEFAULT)
                        } catch (_: Throwable) {
                            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
                        }
                        String(bytes, Charsets.UTF_8)
                    } catch (t: Throwable) {
                        ""
                    }
                }

                override fun urlEncode(value: String): String =
                    URLEncoder.encode(value, "UTF-8")

                override fun urlDecode(value: String): String =
                    URLDecoder.decode(value, "UTF-8")

                override fun joinUrl(base: String, path: String): String {
                    return try {
                        java.net.URI(base).resolve(path).toString()
                    } catch (_: Throwable) {
                        if (path.startsWith("http://", true) || path.startsWith("https://", true)) path
                        else if (base.endsWith("/")) base + path.trimStart('/')
                        else base.substringBeforeLast('/') + "/" + path.trimStart('/')
                    }
                }

                override fun log(message: String) {
                    Log.d("JsScript", message.take(2000))
                }

                override fun sleep(millis: Long) {
                    try {
                        Thread.sleep(millis.coerceIn(0L, 10_000L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }

            ScriptableObject.putProperty(scope, "HttpBridge", RhinoContext.javaToJS(bridge, scope))
            cx.evaluateString(scope, JS_PRELUDE, "prelude", 1, null)
            cx.evaluateString(scope, scriptContent, scriptFile.name, 1, null)

            return invokeEntry(cx, scope, scriptArgs)
        } catch (t: Throwable) {
            Log.e(TAG, "JS Error in $scriptUrl: ${t.message}", t)
            return ""
        } finally {
            jsDeadline.remove()
            if (cx != null) {
                try {
                    RhinoContext.exit()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun invokeEntry(cx: RhinoContext, scope: Scriptable, scriptArgs: String): String {
        val entryNames = arrayOf("getPlayUrl", "getVideoUrl", "getUrl", "play", "main")
        for (name in entryNames) {
            val value = try {
                scope.get(name, scope)
            } catch (_: Throwable) {
                Scriptable.NOT_FOUND
            }
            if (value is Function) {
                val argv: Array<Any> = arrayOf<Any>(scriptArgs)
                val result = value.call(cx, scope, scope, argv)
                return stringify(result)
            }
        }

        // 部分脚本直接给全局 result / exports 赋值。
        val result = try {
            scope.get("result", scope)
        } catch (_: Throwable) {
            Scriptable.NOT_FOUND
        }
        if (result != Scriptable.NOT_FOUND) {
            val text = stringify(result)
            if (text.isNotEmpty()) return text
        }

        val module = try {
            scope.get("module", scope)
        } catch (_: Throwable) {
            Scriptable.NOT_FOUND
        }
        if (module is Scriptable) {
            val exports = try {
                module.get("exports", module)
            } catch (_: Throwable) {
                Scriptable.NOT_FOUND
            }
            if (exports is Function) {
                val argv: Array<Any> = arrayOf<Any>(scriptArgs)
                return stringify(exports.call(cx, scope, scope, argv))
            }
            if (exports != Scriptable.NOT_FOUND) {
                val text = stringify(exports)
                if (text.isNotEmpty()) return text
            }
        }

        return ""
    }

    private fun stringify(value: Any?): String {
        if (value == null || value == Scriptable.NOT_FOUND || value === RhinoContext.getUndefinedValue()) return ""
        val text = try {
            RhinoContext.toString(value)
        } catch (_: Throwable) {
            ""
        }.trim()
        return if (text == "undefined" || text == "null") "" else text
    }

    /**
     * 注入到 JS 全局环境的兼容层。
     * 注意: Kotlin 原始字符串里仍会解析 $, 这里没有使用 $ 符号。
     */
    private val JS_PRELUDE = """
        var console = {
            log: function() { HttpBridge.log(Array.prototype.slice.call(arguments).join(' ')); },
            info: function() { HttpBridge.log(Array.prototype.slice.call(arguments).join(' ')); },
            warn: function() { HttpBridge.log(Array.prototype.slice.call(arguments).join(' ')); },
            error: function() { HttpBridge.log(Array.prototype.slice.call(arguments).join(' ')); }
        };
        var module = { exports: {} };
        var exports = module.exports;

        function req(url, options) {
            options = options || {};
            var method = options.method || 'GET';
            var headers = options.headers || {};
            if (typeof headers === 'string') {
                try { headers = JSON.parse(headers); } catch (e) { headers = {}; }
            }
            var body = options.body;
            if (body === undefined || body === null) body = options.data;
            if (body !== undefined && body !== null && typeof body !== 'string') {
                try { body = JSON.stringify(body); } catch (e) { body = String(body); }
            }
            return HttpBridge.request(String(url), String(method), JSON.stringify(headers),
                (body === undefined || body === null) ? '' : String(body));
        }
        function fetch(url, options) { return req(url, options); }
        function base64Encode(s) { return HttpBridge.base64Encode(String(s)); }
        function base64Decode(s) { return HttpBridge.base64Decode(String(s)); }
        function md5(s) { return HttpBridge.md5(String(s)); }
        function joinUrl(base, path) { return HttpBridge.joinUrl(String(base), String(path)); }
        function urlencode(s) { return HttpBridge.urlEncode(String(s)); }
        function urldecode(s) { return HttpBridge.urlDecode(String(s)); }
        function sleep(ms) { HttpBridge.sleep(Number(ms) || 0); }
    """.trimIndent()
}
