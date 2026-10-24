package com.iptv.tvplayer.script

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@androidx.annotation.Keep
interface HttpBridge {
    @androidx.annotation.Keep fun fetch(url: String): String
    @androidx.annotation.Keep fun fetchWithHeaders(url: String, headersJson: String): String
}

object ScriptManager {
    private const val TAG = "ScriptManager"
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
        .readTimeout(10, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        PhpScriptManager.init(context)
    }

    suspend fun resolveUrl(rawUrl: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // PHP 解析脚本: local://ysp(1).php?id=cctv1 → LocalServer /phpout/ 路由回流 m3u8
                if (PhpScriptManager.matches(rawUrl)) {
                    return@withContext PhpScriptManager.resolve(rawUrl) ?: ""
                }

                val jsRegex = Regex("^(https?://.*?\\.js|local://.*?\\.js|file://.*?\\.js)/(.*)$", RegexOption.IGNORE_CASE)

                val jsMatch = jsRegex.find(rawUrl)
                if (jsMatch != null) {
                    val scriptUrl = jsMatch.groupValues[1]
                    val scriptArgs = jsMatch.groupValues[2]
                    return@withContext executeJs(scriptUrl, scriptArgs)
                }

                return@withContext rawUrl
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve script URL: $rawUrl", e)
                rawUrl
            }
        }
    }

    private fun md5(str: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(str.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun downloadScript(url: String, extension: String): File {
        if (url.startsWith("local://")) {
            val fileName = url.substringAfter("local://")
            val moyuDir = File(android.os.Environment.getExternalStorageDirectory(), "摸鱼TV/js")
            return File(moyuDir, fileName)
        }
        if (url.startsWith("file://")) {
            val filePath = url.substringAfter("file://")
            return File(filePath)
        }

        val cacheDir = File(appContext!!.filesDir, "scripts")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val fileName = md5(url) + extension
        val scriptFile = File(cacheDir, fileName)

        val downloadUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.contains("?")) "$url&_cb=${System.currentTimeMillis()}" else "$url?_cb=${System.currentTimeMillis()}"
        } else {
            url
        }

        try {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val content = resp.body?.string() ?: ""
                if (content.isNotEmpty()) {
                    scriptFile.writeText(content)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download script, using cache if exists", e)
        }

        return scriptFile
    }

    private fun executeJs(scriptUrl: String, scriptArgs: String): String {
        val scriptFile = downloadScript(scriptUrl, ".js")
        if (!scriptFile.exists()) return ""

        val scriptContent = scriptFile.readText()
        return try {
            val cx = org.mozilla.javascript.Context.enter()
            cx.optimizationLevel = -1
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            
            val bridge = object : HttpBridge {

                override fun fetch(url: String): String {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                            .build()
                        return client.newCall(req).execute().body?.string() ?: ""
                    } catch (e: Exception) {
                        return ""
                    }
                }
                override fun fetchWithHeaders(url: String, headersJson: String): String {
                    try {
                        val builder = Request.Builder().url(url)
                        val headersMap = org.json.JSONObject(headersJson)
                        val keys = headersMap.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            builder.addHeader(k, headersMap.getString(k))
                        }
                        return client.newCall(builder.build()).execute().body?.string() ?: ""
                    } catch (e: Exception) {
                        return ""
                    }
                }
            }

            org.mozilla.javascript.ScriptableObject.putProperty(scope, "HttpBridge", org.mozilla.javascript.Context.javaToJS(bridge, scope))
            
            cx.evaluateString(scope, scriptContent, "Script", 1, null)
            
            val callCode = "getPlayUrl('$scriptArgs');"
            val result = cx.evaluateString(scope, callCode, "Call", 1, null)
            
            val resultStr = org.mozilla.javascript.Context.toString(result)
            org.mozilla.javascript.Context.exit()
            
            resultStr

        } catch (e: Exception) {
            Log.e(TAG, "JS Error", e)
            org.mozilla.javascript.Context.exit()
            ""
        }
    }
}
