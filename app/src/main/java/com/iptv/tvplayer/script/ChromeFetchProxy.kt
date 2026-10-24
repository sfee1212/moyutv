package com.iptv.tvplayer.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Chrome 指纹 fetch 代理.
 *
 * 部分服务端 (如 gdtv-api.gdtv.cn) 按 TLS/HTTP2 指纹 (JA3 / Akamai fingerprint) 区分客户端:
 * 对浏览器传输签发可用的播放 token, 对 OkHttp/curl 等非浏览器传输只签发会被 CDN 拒绝 (403)
 * 的诱饵 token。实测同一签名算法/请求头下, 浏览器上下文 fetch 拿到的 token 可直接 200 播放。
 *
 * 本组件维护一个隐藏的 X5 WebView (加载 https://www.gdtv.cn/, 与官方站同源), 把请求以
 * fetch() 形式在页面上下文中执行, 以浏览器原生指纹换取真实响应。仅 /phpfetch 对指纹敏感
 * 的 host 使用; 初始化或执行失败时返回 null, 由调用方回退 OkHttp。
 */
object ChromeFetchProxy {
    private const val TAG = "ChromeFetchProxy"
    private const val PAGE_URL = "https://www.gdtv.cn/"
    private const val INIT_WAIT_MS = 8_000L
    private const val JS_FUNC = """
        (function() {
            window.__cfp = async function(id, url, method, headersJson, bodyB64) {
            try {
                var headers = JSON.parse(headersJson);
                var init = { method: method, headers: headers };
                if (bodyB64) {
                    var bin = atob(bodyB64);
                    var arr = new Uint8Array(bin.length);
                    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                    init.body = arr;
                }
                var r = await fetch(url, init);
                var buf = new Uint8Array(await r.arrayBuffer());
                var s = ''; var CH = 32768;
                for (var i = 0; i < buf.length; i += CH) {
                    s += String.fromCharCode.apply(null, buf.subarray(i, i + CH));
                }
                var hdrs = {};
                r.headers.forEach(function(v, k) { hdrs[k] = v; });
                window.ChromeFetchProxy.onResult(id, r.status, btoa(s), JSON.stringify(hdrs), '');
            } catch (e) {
                try { window.ChromeFetchProxy.onResult(id, 0, '', '{}', String(e)); } catch (e2) {}
            }
        };
            return true;
        })();
    """

    class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val error: String?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idGen = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Response>>()
    private val lock = Any()

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    @Volatile private var initStarted = false
    @Volatile private var initFailed = false

    /**
     * true = WebView 永久不可用 (创建失败, 设备不支持), 调用方应回退 OkHttp;
     * false = 代理仍在初始化/暂态失败, 调用方应硬失败 (status 0) 让上层重试,
     * 避免回退拿到诱饵 token 毒化脚本缓存。
     */
    val isPermanentlyUnavailable: Boolean
        get() = initFailed

    /** 懒初始化 (幂等); 任何异常都只降级为 initFailed, 不影响调用方回退 OkHttp */
    fun ensureInit(context: Context) {
        if (initStarted) return
        synchronized(lock) {
            if (initStarted) return
            initStarted = true
            try {
                mainHandler.post {
                    try {
                        val wv = WebView(context.applicationContext)
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = false
                        // 与官方 PC 站一致的桌面 Chrome UA (fetch 无法覆盖 UA 头, 由浏览器自带)
                        wv.settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        wv.addJavascriptInterface(Bridge(), "ChromeFetchProxy")
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // (重新)注入 fetch 桥 (幂等); 注入脚本返回可序列化值 true,
                                // 确认执行成功后才置 pageReady, 避免首个请求命中未定义的 __cfp
                                injectBridge(view, url, 0)
                            }
                        }
                        wv.loadUrl(PAGE_URL)
                        webView = wv
                        Log.i(TAG, "proxy webview created")
                    } catch (t: Throwable) {
                        Log.w(TAG, "webview create failed: $t")
                        initFailed = true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "webview post failed: $t")
                initFailed = true
            }
        }
    }

    /** 注入 fetch 桥; 成功 (返回 true) 后置 pageReady, 失败重试 2 次后放弃 (调用方回退) */
    private fun injectBridge(view: WebView?, url: String?, attempt: Int) {
        try {
            view?.evaluateJavascript(JS_FUNC) { result ->
                if (result != null && result.contains("true")) {
                    pageReady = true
                    Log.i(TAG, "page ready + js bridge injected: $url")
                } else if (attempt < 2 && !initFailed) {
                    Log.w(TAG, "js bridge injection retry #$attempt, result=$result")
                    view?.postDelayed({ injectBridge(view, url, attempt + 1) }, 500)
                } else {
                    Log.e(TAG, "js bridge injection failed permanently, result=$result")
                    initFailed = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "js bridge injection error: $t")
            initFailed = true
        }
    }

    /**
     * 在 WebView 页面上下文中执行 fetch。返回 null 表示代理不可用 (调用方应回退)。
     * 注意: User-Agent / Origin / Referer / Cookie 等为浏览器保留头, 传入值会被忽略,
     * 由浏览器按页面上下文自动设置 (这正是本组件的目的)。
     */
    fun fetch(
        context: Context,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Long
    ): Response? {
        ensureInit(context)
        val deadline = System.currentTimeMillis() + INIT_WAIT_MS
        while ((webView == null || !pageReady) && !initFailed && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        val wv = webView ?: return null
        if (!pageReady) return null

        val id = idGen.incrementAndGet()
        val future = CompletableFuture<Response>()
        pending[id] = future
        try {
            val headersJson = JSONObject(headers as Map<*, *>).toString()
            val bodyB64 = if (body != null && body.isNotEmpty())
                "'" + Base64.encodeToString(body, Base64.NO_WRAP) + "'" else "null"
            val js = "javascript:try{window.__cfp&&__cfp($id," +
                    jsonStr(url) + "," + jsonStr(method) + "," +
                    jsonStr(headersJson) + ",$bodyB64);}catch(e){}"
            mainHandler.post {
                try { wv.evaluateJavascript(js, null) } catch (t: Throwable) { Log.w(TAG, "eval failed: $t") }
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            pending.remove(id)
            Log.w(TAG, "fetch failed for $method $url: $t")
            return null
        }
    }

    private fun jsonStr(s: String): String = JSONObject.quote(s)

    private class Bridge {
        @JavascriptInterface
        fun onResult(id: Long, status: Int, bodyB64: String, headersJson: String, error: String) {
            val f = pending.remove(id) ?: return
            val body = try { Base64.decode(bodyB64, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
            val headers = HashMap<String, String>()
            try {
                val o = JSONObject(headersJson)
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); headers[k] = o.getString(k) }
            } catch (_: Exception) {}
            f.complete(Response(status, headers, body, error.ifEmpty { null }))
        }
    }
}
