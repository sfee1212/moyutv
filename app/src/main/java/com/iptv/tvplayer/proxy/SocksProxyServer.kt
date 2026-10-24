package com.iptv.tvplayer.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class SocksProxyServer(port: Int = 9090) : NanoHTTPD(port) {

    private val TAG = "SocksProxyServer"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri // e.g. "/127.0.0.1:40000/https:/example.com/playlist.mpd"

        if (uri.length > 1) {
            try {
                val parts = uri.substring(1).split("/", limit = 2)
                if (parts.size == 2) {
                    val ipParam = parts[0]
                    var targetUrl = parts[1]
                    
                    // Fix squashed slashes from URL resolution
                    if (targetUrl.startsWith("https:/") && !targetUrl.startsWith("https://")) {
                        targetUrl = targetUrl.replaceFirst("https:/", "https://")
                    } else if (targetUrl.startsWith("http:/") && !targetUrl.startsWith("http://")) {
                        targetUrl = targetUrl.replaceFirst("http:/", "http://")
                    }
                    
                    if (!session.queryParameterString.isNullOrBlank()) {
                        targetUrl += "?" + session.queryParameterString
                    }
                    
                    Log.d(TAG, "Path Proxying URL: $targetUrl through $ipParam")

                    val proxyParts = ipParam.split(":")
                    val proxy = if (proxyParts.size == 2) {
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyParts[0], proxyParts[1].toInt()))
                    } else {
                        Proxy.NO_PROXY
                    }

                    // Bypass SSL verification
                    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                    })
                    val sc = javax.net.ssl.SSLContext.getInstance("SSL")
                    sc.init(null, trustAllCerts, java.security.SecureRandom())
                    javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
                    javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

                    val url = URL(targetUrl)
                    val conn = url.openConnection(proxy) as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    
                    // Add standard headers
                    conn.setRequestProperty("User-Agent", "okhttp/3.15")
                    conn.setRequestProperty("Accept", "*/*")
                    
                    val responseCode = conn.responseCode
                    if (responseCode !in 200..299) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upstream error: $responseCode")
                    }

                    val contentType = conn.contentType ?: "application/octet-stream"
                    
                    if (targetUrl.contains(".m3u8", ignoreCase = true) || contentType.contains("mpegurl", ignoreCase = true)) {
                        val content = conn.inputStream.bufferedReader().use { it.readText() }
                        val rewrittenContent = rewriteM3u8PathBased(content, targetUrl, ipParam)
                        val bytes = rewrittenContent.toByteArray()
                        return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", ByteArrayInputStream(bytes), bytes.size.toLong())
                    } else if (targetUrl.contains(".mpd", ignoreCase = true) || contentType.contains("dash+xml", ignoreCase = true)) {
                        val content = conn.inputStream.bufferedReader().use { it.readText() }
                        val rewrittenContent = rewriteMpdPathBased(content, targetUrl, ipParam)
                        val bytes = rewrittenContent.toByteArray()
                        return newFixedLengthResponse(Response.Status.OK, "application/dash+xml", ByteArrayInputStream(bytes), bytes.size.toLong())
                    } else {
                        val inputStream = conn.inputStream
                        val res = newChunkedResponse(Response.Status.OK, contentType, inputStream)
                        res.addHeader("Access-Control-Allow-Origin", "*")
                        return res
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy Error", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun rewriteM3u8PathBased(content: String, baseUrl: String, proxyIpPort: String): String {
        val lines = content.lines()
        val builder = StringBuilder()
        
        val baseDir = if (baseUrl.contains("/")) baseUrl.substring(0, baseUrl.lastIndexOf("/") + 1) else baseUrl

        for (line in lines) {
            val trimLine = line.trim()
            if (trimLine.isEmpty() || trimLine.startsWith("#")) {
                builder.append(trimLine).append("\n")
                continue
            }
            
            val targetUrl = if (trimLine.startsWith("http://") || trimLine.startsWith("https://")) {
                trimLine
            } else if (trimLine.startsWith("/")) {
                val urlObj = URL(baseUrl)
                "${urlObj.protocol}://${urlObj.host}${if (urlObj.port != -1) ":" + urlObj.port else ""}$trimLine"
            } else {
                baseDir + trimLine
            }
            
            // Path-based proxy URL
            val proxyUrl = "http://127.0.0.1:9090/$proxyIpPort/$targetUrl"
            builder.append(proxyUrl).append("\n")
        }
        return builder.toString()
    }

    private fun rewriteMpdPathBased(content: String, baseUrl: String, proxyIpPort: String): String {
        // Very basic XML rewriting: prefix absolute URLs with proxy
        // Since ExoPlayer resolves relative URLs against the MPD URL, relative URLs will AUTOMATICALLY work!
        // We only need to rewrite "http://" and "https://" that appear inside attributes like BaseURL, media, initialization, etc.
        val prefix = "http://127.0.0.1:9090/$proxyIpPort/"
        
        val regex = Regex("""(>|=")(https?"://[^"<]+)(<|")""")
        val rewritten = regex.replace(content) { matchResult ->
            val before = matchResult.groupValues[1]
            val url = matchResult.groupValues[2]
            val after = matchResult.groupValues[3]
            
            // Avoid double proxying
            if (url.startsWith("http://127.0.0.1:9090/")) {
                matchResult.value
            } else {
                "$before$prefix$url$after"
            }
        }
        
        return rewritten
    }
}
