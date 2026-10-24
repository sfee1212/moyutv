package com.iptv.tvplayer.proxy

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object StreamProxyHandler {
    private const val TAG = "StreamProxyHandler"
    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    /**
     * A custom SocketFactory that connects through a SOCKS5 proxy using hostname-mode (SOCKS5h).
     * This means the proxy server does the DNS resolution, not the local Android system.
     * This is required because:
     *   1. OkHttp 4.x's built-in SOCKS support breaks on Android 5 with unresolved addresses.
     *   2. Some CDN IPs are only reachable from specific ISP networks; the proxy must resolve the hostname itself.
     */
    private class Socks5hSocketFactory(
        private val proxyHost: String,
        private val proxyPort: Int,
        private val connectTimeoutMs: Int = 10000
    ) : SocketFactory() {

        override fun createSocket(): Socket = Socks5hSocket()
        override fun createSocket(host: String, port: Int): Socket = Socks5hSocket().also { it.connect(InetSocketAddress(host, port), connectTimeoutMs) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostName, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket(host.hostName, port)

        private inner class Socks5hSocket : Socket() {

            /** 读满 n 字节; 代理中途断连(EOF)时抛异常, 绝不能 n += -1 死循环空转 CPU */
            private fun readFully(inp: InputStream, n: Int): ByteArray {
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = inp.read(buf, off, n - off)
                    if (r < 0) throw IOException("SOCKS5 proxy closed during handshake")
                    off += r
                }
                return buf
            }

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val target = endpoint as InetSocketAddress
                // Get the original hostname (not the resolved IP)
                val targetHost = target.hostName ?: target.address?.hostAddress
                    ?: throw IOException("Cannot determine target hostname")
                val targetPort = target.port

                // 1. Connect the raw TCP socket to the SOCKS5 proxy server
                super.connect(InetSocketAddress(proxyHost, proxyPort), if (timeout > 0) timeout else connectTimeoutMs)
                // 关闭 Nagle: 小包请求(播放列表/分片头)经代理时避免 40ms 级延迟
                tcpNoDelay = true

                val out = getOutputStream()
                val inp = getInputStream()

                // 握手阶段限时读, 防止失联代理让请求线程永久挂起(数据阶段由 OkHttp readTimeout 管理)
                val savedTimeout = soTimeout
                soTimeout = connectTimeoutMs
                try {
                    // 2. SOCKS5 greeting: VER=5, NMETHODS=1, METHOD=0x00 (no auth)
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()

                    // 3. Read server greeting response
                    val greet = readFully(inp, 2)
                    if (greet[0] != 0x05.toByte()) throw IOException("Not a SOCKS5 server (ver=${greet[0]})")
                    if (greet[1] != 0x00.toByte()) throw IOException("SOCKS5 auth not supported (method=${greet[1]})")

                    // 4. SOCKS5 CONNECT request with ATYP=0x03 (DOMAINNAME) for SOCKS5h behaviour
                    val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
                    val req = ByteArrayOutputStream()
                    req.write(0x05)            // VER
                    req.write(0x01)            // CMD = CONNECT
                    req.write(0x00)            // RSV
                    req.write(0x03)            // ATYP = DOMAINNAME
                    req.write(hostBytes.size)  // length of domain name
                    req.write(hostBytes)       // domain name bytes
                    req.write((targetPort ushr 8) and 0xFF)  // port high byte
                    req.write(targetPort and 0xFF)            // port low byte
                    out.write(req.toByteArray())
                    out.flush()

                    // 5. Read SOCKS5 reply (minimum 4 bytes: VER, REP, RSV, ATYP)
                    val rep = readFully(inp, 4)
                    if (rep[1] != 0x00.toByte()) throw IOException("SOCKS5 CONNECT failed, REP=${rep[1].toInt()}")

                    // 6. Skip bound address bytes (depends on ATYP)
                    when (rep[3].toInt()) {
                        0x01 -> readFully(inp, 6)    // IPv4(4) + port(2)
                        0x03 -> { val len = inp.read(); if (len < 0) throw IOException("SOCKS5 proxy closed during handshake"); readFully(inp, len + 2) }  // len + domain + port(2)
                        0x04 -> readFully(inp, 18)   // IPv6(16) + port(2)
                    }
                } finally {
                    soTimeout = savedTimeout
                }
                // Socket is now tunnelled to targetHost:targetPort through the SOCKS5 proxy
            }
        }
    }

    private fun getClient(proxyConfig: String): OkHttpClient {
        return clientCache.getOrPut(proxyConfig) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS) // 失效代理快速失败(原15s, 播放器侧15s先放弃, 中继更快返回真实错误)
                .readTimeout(15, TimeUnit.SECONDS) // 静默>15s视为断流: FLV由InfiniteFlvInputStream捕获后自动重连;
                                                   // m3u8/分片避免上游失联时请求线程永久挂起造成线程/连接泄漏(原0=无限挂起)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }

            if (proxyConfig.isNotEmpty()) {
                try {
                    val isSocks = !proxyConfig.startsWith("http", ignoreCase = true)
                    val cleanConfig = proxyConfig
                        .removePrefix("socks5://").removePrefix("socks://")
                        .removePrefix("http://").removePrefix("https://")
                    val parts = cleanConfig.split(":")
                    if (parts.size >= 2) {
                        val host = parts[0]
                        val port = parts[1].toInt()
                        if (isSocks) {
                            // Use custom SOCKS5h SocketFactory — bypasses OkHttp's broken SOCKS5 on Android 5
                            // The factory connects to the proxy and sends the hostname via SOCKS5 ATYP_DOMAINNAME
                            builder.socketFactory(Socks5hSocketFactory(host, port))
                        } else {
                            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid proxy config: $proxyConfig", e)
                }
            }
            builder.build()
        }
    }

    /**
     * 手动跟随 HTTP 重定向，无次数上限限制（最多30 次避免死循环）。
     * 返回最终响应和最终请求（含最终URL）。
     */
    private fun executeWithRedirects(client: OkHttpClient, request: Request, maxRedirects: Int = 30): Response {
        var currentRequest = request
        var redirectCount = 0
        while (true) {
            val response = client.newCall(currentRequest).execute()
            val code = response.code
            if (code in 300..399 && redirectCount < maxRedirects) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) return client.newCall(currentRequest).execute()
                val newUrl = currentRequest.url.resolve(location)
                    ?: return client.newCall(currentRequest).execute()
                currentRequest = currentRequest.newBuilder().url(newUrl).build()
                redirectCount++
                Log.d(TAG, "Redirect [$redirectCount] -> $newUrl")
            } else {
                return response
            }
        }
    }

    /**
     * An InputStream that auto-reconnects to a live FLV stream when the upstream server
     * closes the connection (Connection: Close). This is common for FLV live streams where
     * the server pushes segments and closes the TCP connection periodically.
     *
     * 修复：首次连接成功后记录最终真实URL，后续重连直接使用，避免每次重走长重定向链。
     */
    private class InfiniteFlvInputStream(
        private val client: OkHttpClient,
        private val request: Request,
        initialResponse: Response? = null
    ) : InputStream() {

        private var currentStream: InputStream? = null
        private var currentResponse: Response? = null
        private var closed = false
        private var firstConnect = initialResponse == null
        private var consecutiveErrors = 0
        private val MAX_ERRORS = 10
        private var isReconnection = false

        // proxy
        private var finalRequest: Request = request

        init {
            if (initialResponse != null) {
                // 用初始响应的最终URL 作为后续重连地址
                finalRequest = request.newBuilder()
                    .url(initialResponse.request.url)
                    .build()
                currentResponse = initialResponse
                currentStream = initialResponse.body?.byteStream()
                isReconnection = true
            }
        }

        /** 手动跟随重定向（最多30 次），与外层 executeWithRedirects 逻辑一致*/
        private fun connectWithRedirects(req: Request): Response {
            var current = req
            var count = 0
            while (true) {
                val resp = client.newCall(current).execute()
                if (resp.code in 300..399 && count < 30) {
                    val loc = resp.header("Location")
                    resp.close()
                    if (loc.isNullOrBlank()) return client.newCall(current).execute()
                    val newUrl = current.url.resolve(loc) ?: return client.newCall(current).execute()
                    current = current.newBuilder().url(newUrl).build()
                    count++
                } else {
                    // 更新 finalRequest 为最终到达的 URL，后续重连直接用
                    finalRequest = current
                    return resp
                }
            }
        }

        private fun connect(): Boolean {
            try {
                currentStream?.close()
                currentResponse?.close()
            } catch (e: Exception) { /* ignore */ }

            return try {
                // comment
                val resp = try {
                    connectWithRedirects(finalRequest)
                } catch (e: Exception) {
                    Log.w(TAG, "FLV fast reconnect failed, retrying from origin: ${e.message}")
                    connectWithRedirects(request)
                }

                if (resp.isSuccessful) {
                    val rawStream = resp.body?.byteStream()
                    if (rawStream != null) {
                        if (isReconnection) {
                            // Skip FLV Header (9 bytes) + PreviousTagSize0 (4 bytes) = 13 bytes
                            val headerBuffer = ByteArray(13)
                            var bytesRead = 0
                            while (bytesRead < 13) {
                                val read = rawStream.read(headerBuffer, bytesRead, 13 - bytesRead)
                                if (read == -1) break
                                bytesRead += read
                            }
                            Log.d(TAG, "FLV reconnected to ${finalRequest.url}, skipped FLV header.")
                        }
                        currentStream = rawStream
                        currentResponse = resp
                        consecutiveErrors = 0
                        isReconnection = true
                        true
                    } else {
                        resp.close()
                        false
                    }
                } else {
                    Log.w(TAG, "FLV reconnect got HTTP ${resp.code}")
                    resp.close()
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "FLV reconnect failed: ${e.message}")
                false
            }
        }

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n == -1) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1

            // First connection
            if (firstConnect) {
                firstConnect = false
                if (!connect()) return -1
            }

            while (!closed && consecutiveErrors < MAX_ERRORS) {
                val stream = currentStream ?: run {
                    if (!connect()) {
                        consecutiveErrors++
                        Thread.sleep(500)
                        return@run null
                    }
                    currentStream
                } ?: continue

                val n = try {
                    stream.read(b, off, len)
                } catch (e: Exception) {
                    -1
                }

                if (n == -1) {
                    // Upstream closed ?reconnect immediately for live stream continuity
                    Log.d(TAG, "FLV upstream EOF, reconnecting...")
                    Thread.sleep(200)
                    if (!connect()) {
                        consecutiveErrors++
                        Thread.sleep(500)
                    }
                    continue
                }

                return n
            }
            return -1
        }

        override fun close() {
            closed = true
            try {
                currentResponse?.body?.close()
                currentResponse?.close()
            } catch (e: Exception) { /* ignore */ }
        }
    }


    fun handleRequest(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        Log.d(TAG, "handleRequest: uri=$uri, query=${session.queryParameterString}")
        var targetUrl = ""
        try {
            // URI format: /_p{base64proxy}/http://target...
            // 字符集含 URL_SAFE base64 的 - _ (如代理串为 http://host:port 时编码会出现)
            val pMatch = Regex("^/(_p[A-Za-z0-9+/=_-]*)/(.*)").find(uri)
            if (pMatch == null) {
                Log.w(TAG, "pMatch is null for uri=$uri")
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Invalid proxy route")
            }

            val prefix = pMatch.groupValues[1]
            val encodedProxy = prefix.substring(2)
            val proxyConfig = try {
                String(Base64.decode(encodedProxy, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                ""
            }

            targetUrl = pMatch.groupValues[2]
            
            // Fix squashed slashes from Android NanoHTTPD URI parsing
            if (targetUrl.startsWith("https:/") && !targetUrl.startsWith("https://")) {
                targetUrl = targetUrl.replaceFirst("https:/", "https://")
            } else if (targetUrl.startsWith("http:/") && !targetUrl.startsWith("http://")) {
                targetUrl = targetUrl.replaceFirst("http:/", "http://")
            }

            if (targetUrl.contains("fh.tvbj.cc.cd", ignoreCase = true) && targetUrl.startsWith("http://", ignoreCase = true)) {
                targetUrl = targetUrl.replaceFirst("http://", "https://", ignoreCase = true)
            }

            if (!session.queryParameterString.isNullOrBlank()) {
                targetUrl += "?" + session.queryParameterString
            }

            val isM3u8Path = targetUrl.contains(".m3u8", ignoreCase = true)

            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity") // Prevent OkHttp from requesting and stripping gzip
            
            val ua = session.headers["user-agent"] ?: "okhttp/3.15"
            reqBuilder.header("User-Agent", ua)
            
            session.headers["referer"]?.let { reqBuilder.header("Referer", it) }
            session.headers["cookie"]?.let { reqBuilder.header("Cookie", it) }
            
            // Mirror proxy.txt logic: Strip Range header for M3U8
            val range = session.headers["range"]
            if (!isM3u8Path && range != null) {
                reqBuilder.header("Range", range)
            }

            // 合并为单行日志: 每个分片请求都会执行, 逐条 Log 会向 logd 写十余次, 拖慢低配设备
            Log.d(TAG, "Req headers(client): " + session.headers.entries.joinToString("; ") { "${it.key}=${it.value}" })

            val request = reqBuilder.build()
            Log.d(TAG, "Outgoing request: ${request.url}")
            Log.d(TAG, "Req headers(upstream): " + request.headers.joinToString("; ") { "${it.first}=${it.second}" })

            val client = getClient(proxyConfig)

            // 手动跟随重定向（最多30 次），记录最终真实URL
            val response: Response = executeWithRedirects(client, request)

            val statusCode = response.code
            Log.d(TAG, "Response code: $statusCode | " + response.headers.joinToString("; ") { "${it.first}=${it.second}" })

            val nanoStatus = NanoHTTPD.Response.Status.lookup(statusCode)
                ?: if (statusCode == 206) NanoHTTPD.Response.Status.PARTIAL_CONTENT else NanoHTTPD.Response.Status.OK

            // response.request.url 为最终跟随重定向后的真实 URL
            val contentType = response.header("Content-Type", "application/octet-stream")!!
            val finalUrl = response.request.url.toString()

            if (statusCode >= 400) {
                val bodyStr = try {
                    response.peekBody(2048).string()
                } catch (e: Exception) {
                    "failed to peek body: ${e.message}"
                }
                Log.w(TAG, "Remote server returned HTTP $statusCode for URL: $targetUrl, finalURL: $finalUrl, body: $bodyStr")
            }


            val isFlvResponse = contentType.contains("flv", ignoreCase = true) ||
                                finalUrl.contains(".flv", ignoreCase = true) ||
                                targetUrl.contains(".flv", ignoreCase = true)

            // For FLV live streams, use auto-reconnecting infinite stream wrapping the initial response
            if (isFlvResponse && (statusCode == 200 || statusCode == 206)) {
                val infiniteStream = InfiniteFlvInputStream(client, request, response)
                val res = NanoHTTPD.newChunkedResponse(nanoStatus, contentType, infiniteStream)
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            }

            val isM3u8Response = (statusCode == 200 || statusCode == 206) &&
                    (contentType.contains("mpegurl", ignoreCase = true) || (isM3u8Path && !contentType.contains("text/html", ignoreCase = true)))

            if (isM3u8Response) {
                val bodyText = response.body?.string() ?: ""
                val finalUrl = response.request.url.toString()
                val rewritten = rewriteM3u8(bodyText, prefix, finalUrl)
                val bytes = rewritten.toByteArray()
                
                val res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", ByteArrayInputStream(bytes), bytes.size.toLong())
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                res.addHeader("Pragma", "no-cache")
                res.addHeader("Expires", "0")
                return res
            } else {
                val body = response.body
                if (body == null) {
                    return NanoHTTPD.newFixedLengthResponse(nanoStatus, contentType, "")
                }
                
                val contentLength = body.contentLength()
                // 弱网平滑: 边发边预取(最多512KB), 上游抖动期间由缓冲继续供数, 减少播放端停顿
                val smoothed = PrefetchInputStream(body.byteStream())
                val res = if (contentLength > 0) {
                    NanoHTTPD.newFixedLengthResponse(nanoStatus, contentType, smoothed, contentLength)
                } else {
                    NanoHTTPD.newChunkedResponse(nanoStatus, contentType, smoothed)
                }
                
                // Copy safe headers
                val contentRange = response.header("Content-Range")
                if (contentRange != null) res.addHeader("Content-Range", contentRange)
                
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy Error [${e.javaClass.simpleName}]: ${e.message} | target=$targetUrl")
            if (targetUrl.contains("fh.tvbj.cc.cd", ignoreCase = true)) {
                val channelKey = when {
                    targetUrl.contains("fhzx", ignoreCase = true) -> "fhzx"
                    targetUrl.contains("fhzw", ignoreCase = true) -> "fhzw"
                    targetUrl.contains("fhhk", ignoreCase = true) -> "fhhk"
                    else -> "fhzw"
                }
                val resolvedUrl = resolvePhoenixUrl(channelKey)
                if (resolvedUrl.isNotEmpty()) {
                    val res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, "text/plain", "")
                    res.addHeader("Location", resolvedUrl)
                    return res
                }
            }
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }

    private fun rewriteM3u8(content: String, proxyPrefix: String, finalUrl: String): String {
        val lines = content.lines()
        val workerOrigin = "http://127.0.0.1:8080/$proxyPrefix"
        val baseUrl = finalUrl.toHttpUrlOrNull()
        var finalQueryString = baseUrl?.query
        val isCatchup = (finalQueryString?.contains("starttime=") == true && finalQueryString?.contains("endtime=") == true) || finalQueryString?.contains("playseek=") == true
        
        var segmentQueryString = finalQueryString
        if (isCatchup && segmentQueryString != null) {
            finalQueryString = finalQueryString!!.replace("livemode=1", "livemode=4")
            segmentQueryString = segmentQueryString!!.split("&").filter {
                !it.startsWith("starttime=") && !it.startsWith("endtime=") && !it.startsWith("playseek=")
            }.joinToString("&")
            if (segmentQueryString!!.isEmpty()) segmentQueryString = null
        }
        
        return lines.joinToString("\n") { line ->
            val trimmed = line.trim()
            
            // Rewrite URI="..." attributes in tags
            if (trimmed.startsWith("#") && trimmed.contains("URI=\"", ignoreCase = true)) {
                val match = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(trimmed)
                if (match != null) {
                    val uriVal = match.groupValues[1]
                    var absoluteUrl = baseUrl?.resolve(uriVal)?.toString() ?: uriVal
                    
                    if (isCatchup) {
                        absoluteUrl = absoluteUrl.replace("livemode=1", "livemode=4")
                        absoluteUrl = absoluteUrl.replace("/180000001001/", "/180000002001/")
                        absoluteUrl = absoluteUrl.replace("/180000001002/", "/180000002002/")
                        absoluteUrl = absoluteUrl.replace("/200000001898/", "/200000002898/")
                    }

                    if (!segmentQueryString.isNullOrBlank()) {
                        val params = segmentQueryString.split("&").filter { it.isNotBlank() }
                        for (param in params) {
                            val key = param.substringBefore("=")
                            if (!absoluteUrl.contains("$key=")) {
                                absoluteUrl += if (absoluteUrl.contains("?")) "&$param" else "?$param"
                            }
                        }
                    }
                    return@joinToString line.replace(uriVal, "$workerOrigin/$absoluteUrl")
                }
            }
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@joinToString line
            }

            // Rewrite segment URLs to absolute URLs using the final post-redirect URL
            var absoluteUrl = baseUrl?.resolve(trimmed)?.toString() ?: trimmed
            
            if (isCatchup) {
                absoluteUrl = absoluteUrl.replace("livemode=1", "livemode=4")
                absoluteUrl = absoluteUrl.replace("/180000001001/", "/180000002001/")
                absoluteUrl = absoluteUrl.replace("/180000001002/", "/180000002002/")
                absoluteUrl = absoluteUrl.replace("/200000001898/", "/200000002898/")
            }

            if (!segmentQueryString.isNullOrBlank()) {
                val params = segmentQueryString.split("&").filter { it.isNotBlank() }
                for (param in params) {
                    val key = param.substringBefore("=")
                    if (!absoluteUrl.contains("$key=")) {
                        absoluteUrl += if (absoluteUrl.contains("?")) "&$param" else "?$param"
                    }
                }
            }
            "$workerOrigin/$absoluteUrl"
        }
    }

    private fun resolvePhoenixUrl(channelKey: String): String {
        val liveId = when (channelKey) {
            "fhzw" -> "f7f48462-9b13-485b-8101-7b54716411ec"
            "fhzx" -> "7c96b084-60e1-40a9-89c5-682b994fb680"
            "fhhk" -> "15e02d92-1698-416c-af2f-3e9a872b4d78"
            else -> "f7f48462-9b13-485b-8101-7b54716411ec"
        }
        
        val localClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
            
        // Try web API first
        val urlWeb = "https://api.fengshows.cn/hub/live/auth-url?live_id=${liveId}&live_qa=HD"
        try {
            val req = Request.Builder()
                .url(urlWeb)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .build()
            val resp = localClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                val playUrl = json.optJSONObject("data")?.optString("live_url", "") ?: ""
                if (playUrl.isNotEmpty()) return playUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web API failed: ${e.message}")
        }
        
        // Try app API fallback
        val urlApp = "https://m.fengshows.com/api/v3/hub/live/auth-url?live_id=${liveId}&live_qa=HD"
        try {
            val req = Request.Builder()
                .url(urlApp)
                .header("User-Agent", "okhttp/3.14.9")
                .build()
            val resp = localClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                val playUrl = json.optJSONObject("data")?.optString("live_url", "") ?: ""
                if (playUrl.isNotEmpty()) return playUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "App API failed: ${e.message}")
        }
        
        return ""
    }
}
