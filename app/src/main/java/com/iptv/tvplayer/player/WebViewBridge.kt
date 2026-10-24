package com.iptv.tvplayer.player

import android.webkit.JavascriptInterface
import com.tencent.smtt.sdk.WebView
import com.iptv.tvplayer.InterceptHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class WebViewBridge(
    private val webView: WebView,
    private val onPlaying: (() -> Unit)? = null,
    private val onError: (() -> Unit)? = null
) {
    private var hasRedirected = false

    @JavascriptInterface
    fun loadIframeUrl(url: String, referer: String) {
        if (hasRedirected) return
        hasRedirected = true
        webView.post {
            val headers = HashMap<String, String>()
            headers["Referer"] = referer
            webView.loadUrl(url, headers)
        }
    }

    @JavascriptInterface
    fun onVideoPlaying() {
        onPlaying?.invoke()
        com.iptv.tvplayer.player.NativePlayerManager.cancelTimeoutJob()
    }

    @JavascriptInterface
    fun onVideoError() {
        onError?.invoke()
        com.iptv.tvplayer.player.NativePlayerManager.onAutoSwitchRequested?.invoke()
    }

    @JavascriptInterface
    fun proxyPost(url: String, headersJson: String, body: String, callbackId: String) {
        val ua = webView.settings.userAgentString
        val cookie = com.tencent.smtt.sdk.CookieManager.getInstance().getCookie(url)
        webView.post {
            Thread {
                try {
                    val client = InterceptHttpClient.client
                    val reqBuilder = okhttp3.Request.Builder().url(url)
                    
                    var hasUserAgent = false
                    var hasCookie = false
                    var hasOrigin = false
                    var hasReferer = false
                    var contentType = "application/x-www-form-urlencoded; charset=utf-8"
                    
                    val headersMap = org.json.JSONObject(headersJson)
                    headersMap.keys().forEach { key ->
                        val value = headersMap.getString(key)
                        reqBuilder.addHeader(key, value)
                        val lowerKey = key.lowercase()
                        if (lowerKey == "user-agent") hasUserAgent = true
                        if (lowerKey == "cookie") hasCookie = true
                        if (lowerKey == "origin") hasOrigin = true
                        if (lowerKey == "referer") hasReferer = true
                        if (lowerKey == "content-type") contentType = value
                    }
                    
                    if (!hasUserAgent && !ua.isNullOrEmpty()) {
                        reqBuilder.addHeader("User-Agent", ua)
                    }
                    if (!hasCookie && !cookie.isNullOrEmpty()) {
                        reqBuilder.addHeader("Cookie", cookie)
                    }
                    if (!hasOrigin) {
                        reqBuilder.addHeader("Origin", "https://yangshipin.cn")
                    }
                    if (!hasReferer) {
                        reqBuilder.addHeader("Referer", "https://yangshipin.cn/tv/home?pid=600002483")
                    }
                    
                    val mediaType = contentType.toMediaTypeOrNull()
                    val requestBody = okhttp3.RequestBody.create(mediaType, body)
                    reqBuilder.post(requestBody)
                    
                    val response = client.newCall(reqBuilder.build()).execute()
                    val statusCode = response.code
                    val responseText = response.body?.string() ?: ""
                    android.util.Log.d("ProxyManager", "=== WebViewBridge.proxyPost ===")
                    android.util.Log.d("ProxyManager", "URL: " + url)
                    android.util.Log.d("ProxyManager", "Headers: " + headersJson)
                    android.util.Log.d("ProxyManager", "ReqBody: " + body)
                    android.util.Log.d("ProxyManager", "Status: " + statusCode)
                    android.util.Log.d("ProxyManager", "RespBody: " + responseText)
                    
                    val respHeadersObj = org.json.JSONObject()
                    response.headers.forEach { (name, value) ->
                        respHeadersObj.put(name.lowercase(), value)
                    }
                    response.close()
                    
                    val escapedText = escapeJsString(responseText)
                    val escapedHeaders = escapeJsString(respHeadersObj.toString())
                    
                    webView.post {
                        webView.loadUrl("javascript:try { window['$callbackId']($statusCode, '$escapedText', '$escapedHeaders'); } catch(e) {}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebViewBridge", "Error in proxyPost: ${e.message}")
                    webView.post {
                        webView.loadUrl("javascript:try { window['$callbackId'](500, '', '{}'); } catch(e) {}")
                    }
                }
            }.start()
        }
    }
    
    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }
}
