package com.iptv.tvplayer.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers

class LocalServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    init {
        // 播放列表/分片等小响应分"响应头+正文"两次小写入, 默认 Nagle 下第二次写入会等
        // 对端延迟ACK(≈40ms), 实测每个 keep-alive 请求多耗 ~50ms → 直接在 accept 时关闭 Nagle。
        setServerSocketFactory(object : NanoHTTPD.ServerSocketFactory {
            override fun create(): java.net.ServerSocket = object : java.net.ServerSocket() {
                @Throws(java.io.IOException::class)
                override fun accept(): java.net.Socket {
                    val s = super.accept()
                    try { s.tcpNoDelay = true } catch (_: Exception) {}
                    return s
                }
            }
        })
    }

    private fun String.fixEncoding(): String {
        if (this.all { it.code <= 255 }) {
            try {
                val bytes = this.toByteArray(Charsets.ISO_8859_1)
                val utf8 = String(bytes, Charsets.UTF_8)
                if (!utf8.contains('\uFFFD')) return utf8
                val gbk = String(bytes, java.nio.charset.Charset.forName("GBK"))
                if (!gbk.contains('\uFFFD')) return gbk
            } catch (e: Exception) {}
        }
        return this
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.GET && uri == "/") {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>推送网络列表</title>
                    <style>
                        * { box-sizing: border-box; }
                        body { font-family: sans-serif; text-align: center; padding: 20px; background: #f0f0f0; }
                        .container { background: white; padding: 30px; border-radius: 12px; display: block; margin: 0 auto 20px auto; width: 90%; max-width: 500px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); text-align: center; }
                        .container h2 { margin-top: 0; font-size: 20px; color: #333; margin-bottom: 20px; }
                        input[type=text], input[type=url], textarea, input[type=file] { margin: 8px 0; padding: 12px; width: 100%; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; background-color: #fafafa; display: block; }
                        textarea { resize: vertical; }
                        input[type=submit] { background: #007BFF; color: white; border: none; padding: 12px; width: 100%; border-radius: 6px; cursor: pointer; margin-top: 10px; font-size: 16px; font-weight: bold; display: block; }
                        input[type=submit]:hover { opacity: 0.9; }
                        label { display: block; text-align: left; font-size: 14px; color: #555; margin-top: 10px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h2>推送网络列表链接</h2>
                        <form method="POST" action="/push_url">
                            <input type="text" name="list_name" placeholder="列表命名 (例如: 我的电视)" required />
                            <input type="url" name="list_url" placeholder="网络链接 (例如: http://...m3u)" required />
                            <input type="submit" value="推送到电视" />
                        </form>
                    </div>
                    
                    <div class="container">
                        <h2>推送自定义 EPG 链接</h2>
                        <form method="POST" action="/push_epg">
                            <input type="text" name="epg_name" placeholder="EPG 命名 (例如: 我的EPG)" required />
                            <input type="url" name="epg_url" placeholder="EPG 链接 (例如: http://...xml.gz)" required />
                            <input type="submit" value="推送到电视" style="background: #28a745;" />
                        </form>
                    </div>

                    <div class="container">
                        <h2>推送 SOCKS5 代理组</h2>
                        <form method="POST" action="/push_socks5">
                            <input type="text" name="socks_name" placeholder="代理组名称 (例如: 广东组)" required />
                            <textarea name="socks_ips" placeholder="SOCKS5 代理 IP 列表，每行一个 (例如: 192.168.1.1:1080)" rows="3" required></textarea>
                            <input type="submit" value="推送到电视" style="background: #6f42c1;" />
                        </form>
                    </div>
                    
                    <div class="container">
                        <h2>推送高级设置</h2>
                        <form method="POST" action="/push_advanced">
                            <input type="url" name="youtube_proxy" placeholder="代理订阅池链接(例如 http://...)" />
                            <input type="submit" value="推送到电视" style="background: #ffc107; color: black;" />
                        </form>
                    </div>
                    
                    <div class="container">
                        <h2>推送本地文件到电视</h2>
                        <form method="POST" action="/push_file" enctype="multipart/form-data" onsubmit="this.action='/push_file?list_filename=' + encodeURIComponent(this.list_file.files[0] ? this.list_file.files[0].name : '') + '&js_filename=' + encodeURIComponent(this.js_file.files[0] ? this.js_file.files[0].name : '') + '&php_filename=' + encodeURIComponent(this.php_file.files[0] ? this.php_file.files[0].name : ''); return true;">
                            <label>上传本地播放列表 (.txt, .m3u):</label>
                            <input type="file" name="list_file" accept=".txt,.m3u,.m3u8,*/*" />
                            <label>上传本地JS脚本 (.js):</label>
                            <input type="file" name="js_file" accept=".js,application/javascript,text/javascript,*/*" />
                            <label>上传解析脚本 (.php / .py):</label>
                            <input type="file" name="php_file" accept=".php,.py,text/x-php,*/*" />
                            <input type="submit" value="上传推送到电视" style="background: #17a2b8;" />
                        </form>
                    </div>
                </body>
                </html>            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
        } else if (method == Method.GET && uri.startsWith("/_p")) {
            return com.iptv.tvplayer.proxy.StreamProxyHandler.handleRequest(session, uri)
        } else if (method == Method.GET && uri == "/proxy_node") {
            val proxyUrl = com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl.trim()
            if (proxyUrl.isNotEmpty()) {
                val base64 = android.util.Base64.encodeToString(proxyUrl.toByteArray(), android.util.Base64.NO_WRAP)
                return newFixedLengthResponse(Response.Status.OK, "text/plain", base64)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        } else if (method == Method.POST && uri == "/push_url") {
            try {
                session.parseBody(HashMap())
                val parms = session.parameters
                val listName = (parms["list_name"]?.firstOrNull() ?: "自定义推送源").fixEncoding()
                val listUrl = (parms["list_url"]?.firstOrNull() ?: "").fixEncoding()
                
                if (listUrl.isNotEmpty()) {
                    // Update SettingsManager
                    val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                    // Remove if exists
                    currentSubs.removeAll { it.url == listUrl }
                    // Add new
                    currentSubs.add(com.iptv.tvplayer.data.Subscription(listName, listUrl))
                    com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                    
                    val html = """
                        <html><body>
                        <h2 style='text-align:center; padding: 50px; color: green;'>推送成功</h2>
                        <p style='text-align:center;'>请在电视端的订阅源设置中选择: ${listName}</p>
                        <script>setTimeout(function(){window.location.href='/';}, 3000);</script>
                        </body></html>
                    """.trimIndent()
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "参数错误: 链接不能为空")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "推送失败: ${e.message}")
            }
        } else if (method == Method.POST && uri == "/push_epg") {
            try {
                session.parseBody(HashMap())
                val parms = session.parameters
                val epgName = (parms["epg_name"]?.firstOrNull() ?: "自定义EPG").fixEncoding()
                val epgUrl = (parms["epg_url"]?.firstOrNull() ?: "").fixEncoding()
                
                if (epgUrl.isNotEmpty()) {
                    val currentEpgs = com.iptv.tvplayer.data.SettingsManager.epgs.toMutableList()
                    currentEpgs.removeAll { it.url == epgUrl }
                    currentEpgs.add(com.iptv.tvplayer.data.Subscription(epgName, epgUrl))
                    com.iptv.tvplayer.data.SettingsManager.epgs = currentEpgs
                    
                    val html = """
                        <html><body>
                        <h2 style='text-align:center; padding: 50px; color: green;'>推送成功</h2>
                        <p style='text-align:center;'>请在电视端的EPG源设置中选择: ${epgName}</p>
                        <script>setTimeout(function(){window.location.href='/';}, 3000);</script>
                        </body></html>
                    """.trimIndent()
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "参数错误: 链接不能为空")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "推送失败: ${e.message}")
            }
        } else if (method == Method.POST && uri == "/push_socks5") {
            try {
                session.parseBody(HashMap())
                val parms = session.parameters
                val socksName = (parms["socks_name"]?.firstOrNull() ?: "").fixEncoding()
                val socksIps = (parms["socks_ips"]?.firstOrNull() ?: "").fixEncoding()
                
                if (socksName.isNotEmpty() && socksIps.isNotEmpty()) {
                    val ipsList = socksIps.split(Regex("[\n/,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                    
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val validIps = com.iptv.tvplayer.utils.ProxyUtils.filterValidSocks5Ips(ipsList)
                        if (validIps.isNotEmpty()) {
                            val currentGroups = com.iptv.tvplayer.data.SettingsManager.customSocksGroups.toMutableList()
                            currentGroups.removeAll { it.name == socksName }
                            currentGroups.add(com.iptv.tvplayer.data.SocksGroup(socksName, validIps))
                            com.iptv.tvplayer.data.SettingsManager.customSocksGroups = currentGroups
                        }
                    }
                    
                    val html = """
                        <html><body>
                        <h2 style='text-align:center; padding: 50px; color: green;'>SOCKS5 代理组已接收</h2>
                        <p style='text-align:center;'>系统正在后台测速验证节点 (${ipsList.size}个)，验证通过的有效节点将自动保存到代理组: ${socksName} 中</p>
                        <script>setTimeout(function(){window.location.href='/';}, 4000);</script>
                        </body></html>
                    """.trimIndent()
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "参数错误: 名称和IP列表不能为空")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "推送失败: ${e.message}")
            }
        } else if (method == Method.POST && uri == "/push_advanced") {
            try {
                session.parseBody(HashMap())
                val parms = session.parameters
                val youtubeProxy = (parms["youtube_proxy"]?.firstOrNull() ?: "").fixEncoding()
                
                var msg = ""
                if (youtubeProxy.isNotEmpty()) {
                    com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl = youtubeProxy
                    com.iptv.tvplayer.proxy.MihomoProxyManager.restart(context)
                    msg += "代理池已更新"
                }
                
                val html = """
                    <html><body>
                    <h2 style='text-align:center; padding: 50px; color: green;'>高级设置推送成功</h2>
                    <p style='text-align:center;'>$msg</p>
                    <script>setTimeout(function(){window.location.href='/';}, 3000);</script>
                    </body></html>
                """.trimIndent()
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "推送失败: ${e.message}")
            }
        } else if (method == Method.POST && uri.startsWith("/push_file")) {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val parms = session.parameters
                
                var msg = ""
                val base = java.io.File(android.os.Environment.getExternalStorageDirectory(), "摸鱼TV")
                
                // Check if list_file uploaded
                val listTempPath = files["list_file"]
                if (!listTempPath.isNullOrBlank() && java.io.File(listTempPath).exists()) {
                    var fileName = (parms["list_filename"]?.firstOrNull() ?: parms["list_file"]?.firstOrNull() ?: "").fixEncoding()
                    if (fileName.isBlank() || fileName.length > 100 || fileName.contains("?")) fileName = "list_${System.currentTimeMillis()}.txt"
                    
                    var targetDir = java.io.File(base, "list")
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        targetDir = java.io.File(context.getExternalFilesDir(null), "list")
                        targetDir.mkdirs()
                    }
                    val targetFile = java.io.File(targetDir, fileName)
                    
                    java.io.File(listTempPath).copyTo(targetFile, overwrite = true)
                    
                    // Auto add to subscription
                    val listUrl = "file://${targetFile.absolutePath}"
                    
                    // Extract name without extension for better UI display
                    var subName = fileName
                    if (subName.endsWith(".txt", ignoreCase = true)) subName = subName.dropLast(4)
                    if (subName.endsWith(".m3u", ignoreCase = true)) subName = subName.dropLast(4)
                    if (subName.endsWith(".m3u8", ignoreCase = true)) subName = subName.dropLast(5)
                    
                    val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                    currentSubs.removeAll { it.url == listUrl }
                    currentSubs.add(com.iptv.tvplayer.data.Subscription(subName, listUrl))
                    com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                    
                    msg += "<br/>成功上传列表: $subName 并已自动挂载"
                }
                
                // Check if js_file uploaded
                val jsTempPath = files["js_file"]
                if (!jsTempPath.isNullOrBlank() && java.io.File(jsTempPath).exists()) {
                    var fileName = (parms["js_filename"]?.firstOrNull() ?: parms["js_file"]?.firstOrNull() ?: "").fixEncoding()
                    if (fileName.isBlank() || fileName.length > 100 || fileName.contains("?")) fileName = "script_${System.currentTimeMillis()}.js"
                    
                    var targetDir = java.io.File(base, "js")
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        targetDir = java.io.File(context.getExternalFilesDir(null), "js")
                        targetDir.mkdirs()
                    }
                    val targetFile = java.io.File(targetDir, fileName)
                    
                    java.io.File(jsTempPath).copyTo(targetFile, overwrite = true)
                    
                    msg += "<br/>成功上传JS脚本: $fileName 到 摸鱼TV/js 目录"
                }
                
                // Check if php_file uploaded
                val phpTempPath = files["php_file"]
                if (!phpTempPath.isNullOrBlank() && java.io.File(phpTempPath).exists()) {
                    var fileName = (parms["php_filename"]?.firstOrNull() ?: parms["php_file"]?.firstOrNull() ?: "").fixEncoding()
                    if (fileName.isBlank() || fileName.length > 100 || fileName.contains("?")) fileName = "script_${System.currentTimeMillis()}.php"
                    
                    // .py → 摸鱼TV/py, .php → 摸鱼TV/php
                    val subDir = if (fileName.endsWith(".py", ignoreCase = true)) "py" else "php"
                    var targetDir = java.io.File(base, subDir)
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        targetDir = java.io.File(context.getExternalFilesDir(null), subDir)
                        targetDir.mkdirs()
                    }
                    val targetFile = java.io.File(targetDir, fileName)
                    
                    java.io.File(phpTempPath).copyTo(targetFile, overwrite = true)
                    
                    msg += "<br/>成功上传解析脚本: $fileName 到 摸鱼TV/$subDir 目录"
                }
                
                if (msg.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "请选择要上传的文件")
                }
                
                val html = """
                    <html><body>
                    <h2 style='text-align:center; padding: 50px; color: green;'>文件推送成功</h2>
                    <p style='text-align:center;'>$msg</p>
                    <script>setTimeout(function(){window.location.href='/';}, 3000);</script>
                    </body></html>
                """.trimIndent()
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "上传失败: ${e.message}")
            }
        } else if (method == Method.GET && uri.startsWith("/phpout/")) {
            return servePhp(session, uri)
        } else if (method == Method.GET && uri.startsWith("/pyproxy/")) {
            return servePyProxy(session, uri)
        } else if (method == Method.GET && uri.startsWith("/phpfetch")) {
            return servePhpFetch(session)
        } else if (method == Method.GET && uri.startsWith("/rtpts")) {
            return serveRtpTs(session)
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    /**
     * /rtpts?url=<urlencoded rtp://|udp://host:port> — RTP/UDP 组播直播 → HTTP MPEG-TS 回流。
     * Media3/ExoPlayer 无 UDP 数据源, EXO 播放 rtp:// 频道时改写到本端点。
     * 仅接受回环调用, 避免成为局域网开放代理。
     */
    private fun serveRtpTs(session: IHTTPSession): Response {
        val remote = try { session.remoteIpAddress } catch (e: Exception) { null }
        val loopback = remote == null || remote == "127.0.0.1" || remote == "::1" ||
                remote == "0:0:0:0:0:0:0:1" || remote == "localhost"
        if (!loopback) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "loopback only")
        }
        val target = session.parameters?.get("url")?.firstOrNull() ?: ""
        if (!com.iptv.tvplayer.proxy.RtpTsInputStream.isRtpOrUdpUrl(target)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "需要 rtp:// 或 udp:// 地址")
        }
        return try {
            Log.i(TAG, "rtpts >> $target")
            val src = com.iptv.tvplayer.proxy.RtpTsInputStream(context, target)
            val resp = newChunkedResponse(Response.Status.OK, "video/mp2t", src)
            resp.addHeader("Cache-Control", "no-cache, no-store")
            resp
        } catch (e: Exception) {
            Log.e(TAG, "rtpts open failed: $target", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=UTF-8", "RTP打开失败: ${e.message}")
        }
    }

    /** 执行 PHP 解析脚本(见 PhpScriptManager), 返回 m3u8 内容或 302 跳转 */
    private fun servePhp(session: IHTTPSession, uri: String): Response {
        return try {
            var name = uri.removePrefix("/phpout/")
            val qIdx = name.indexOf('?')
            if (qIdx >= 0) name = name.substring(0, qIdx)
            if (name.contains("%")) {
                name = try { java.net.URLDecoder.decode(name, "UTF-8") } catch (e: Exception) { name }
            }
            if (name.endsWith(".m3u8", ignoreCase = true)) name = name.dropLast(5)
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "非法脚本名")
            }
            val params = HashMap<String, String>()
            session.parameters?.forEach { (k, v) -> params[k] = v?.firstOrNull() ?: "" }
            when (val result = com.iptv.tvplayer.script.PhpScriptManager.execute(name, params)) {
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.M3u8 -> {
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", result.content)
                    resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    resp
                }
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Redirect -> {
                    val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                    resp.addHeader("Location", result.url)
                    resp.addHeader("Cache-Control", "no-cache")
                    resp
                }
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Json -> {
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", result.content)
                    resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    resp
                }
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Proxy -> {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "代理结果不应出现在 /phpout 路由")
                }
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Error -> {
                    val status = if (result.status == 404) Response.Status.NOT_FOUND else Response.Status.INTERNAL_ERROR
                    newFixedLengthResponse(status, "text/plain; charset=UTF-8", result.message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "PHP执行失败: ${e.message}")
        }
    }

    /**
     * /pyproxy — CatVod 爬虫 localProxy 回环端点。
     * URL 形如 /pyproxy/<脚本名>?p=<urlencode(JSON)>, 由 base.spider.Spider.getProxyUrl 生成;
     * 转调脚本的 localProxy(param), 将 [状态码, MIME, 正文, 头] 结果回流给播放器。
     */
    private fun servePyProxy(session: IHTTPSession, uri: String): Response {
        return try {
            var name = uri.removePrefix("/pyproxy/")
            val qIdx = name.indexOf('?')
            if (qIdx >= 0) name = name.substring(0, qIdx)
            if (name.contains("%")) {
                name = try { java.net.URLDecoder.decode(name, "UTF-8") } catch (e: Exception) { name }
            }
            // getProxyUrl 生成的地址带 .m3u8 假后缀 (供播放器判定 HLS 类型), 这里剥掉
            if (name.endsWith(".m3u8", ignoreCase = true)) name = name.dropLast(5)
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "非法脚本名")
            }
            val param = session.parameters?.get("p")?.firstOrNull() ?: "{}"
            when (val result = com.iptv.tvplayer.script.PhpScriptManager.pyProxy(name, param)) {
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Proxy -> {
                    val bodyBytes = result.body.toByteArray(Charsets.UTF_8)
                    val st = Response.Status.lookup(result.status) ?: Response.Status.OK
                    val resp = newFixedLengthResponse(st, result.mime, java.io.ByteArrayInputStream(bodyBytes), bodyBytes.size.toLong())
                    result.headers.forEach { (k, v) -> resp.addHeader(k, v) }
                    resp.addHeader("Cache-Control", "no-cache")
                    resp
                }
                is com.iptv.tvplayer.script.PhpScriptManager.PhpResult.Error -> {
                    val status = if (result.status == 404) Response.Status.NOT_FOUND else Response.Status.INTERNAL_ERROR
                    newFixedLengthResponse(status, "text/plain; charset=UTF-8", result.message)
                }
                else -> newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "未知 localProxy 结果")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "pyproxy 执行失败: ${e.message}")
        }
    }

    /**
     * /phpfetch — 本机回环取流端点。
     *
     * 内置 PHP 二进制无任何 TLS 能力(未编译 openssl 扩展、libcurl 未含 https 协议),
     * 解析脚本里的 https 请求由 assets/php/https_wrapper.php 的流包装器/curl 垫片
     * 转成对本端点的纯 http 回环调用, 真实 TLS、重定向、请求头由应用层 OkHttp 完成。
     * 结果以 X-Up-Status / X-Up-Resp(原始响应头块, base64) / X-Up-Err / X-Up-Final-Url + 正文回给 PHP。
     * 仅接受回环地址的调用, 避免成为局域网开放代理。
     */
    private fun servePhpFetch(session: IHTTPSession): Response {
        val remote = try { session.remoteIpAddress } catch (e: Exception) { null }
        val loopback = remote == null || remote == "127.0.0.1" || remote == "::1" ||
                remote == "0:0:0:0:0:0:0:1" || remote == "localhost"
        if (!loopback) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "loopback only")
        }
        fun b64(s: String): String =
            android.util.Base64.encodeToString(s.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)
        fun respond(status: Int, respHeaders: String, err: String, finalUrl: String, body: ByteArray): Response {
            val r = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", body.inputStream(), body.size.toLong())
            r.addHeader("X-Up-Status", status.toString())
            if (respHeaders.isNotEmpty()) r.addHeader("X-Up-Resp", respHeaders)
            if (err.isNotEmpty()) r.addHeader("X-Up-Err", err)
            if (finalUrl.isNotEmpty()) r.addHeader("X-Up-Final-Url", finalUrl)
            r.addHeader("Cache-Control", "no-store")
            return r
        }
        return try {
            val p = session.parameters
            val target = p?.get("url")?.firstOrNull() ?: ""
            if (target.isEmpty()) {
                return respond(0, "", b64("missing url"), "", ByteArray(0))
            }
            val m = (p?.get("method")?.firstOrNull() ?: "GET").uppercase(java.util.Locale.ROOT)
            val method = if (m in setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")) m else "GET"
            Log.d(TAG, "phpfetch >> $method $target")
            val hdrBytes = try {
                android.util.Base64.decode(p?.get("headers")?.firstOrNull() ?: "", android.util.Base64.DEFAULT)
            } catch (e: Exception) { ByteArray(0) }
            val reqBody = try {
                android.util.Base64.decode(p?.get("data")?.firstOrNull() ?: "", android.util.Base64.DEFAULT)
            } catch (e: Exception) { ByteArray(0) }
            val follow = (p?.get("follow")?.firstOrNull() ?: "1") != "0"
            val timeoutSec = (p?.get("timeout")?.firstOrNull()?.toIntOrNull() ?: 10).coerceIn(1, 10)

            // TLS 指纹敏感 host: 经 Chrome (WebView) 上下文取真实响应, 失败回退 OkHttp
            val fpHost = try { java.net.URI(target).host ?: "" } catch (e: Exception) { "" }
            if (fpHost == "gdtv-api.gdtv.cn") {
                val hdrMap = LinkedHashMap<String, String>()
                String(hdrBytes, Charsets.ISO_8859_1).split("\r\n", "\n").forEach { line ->
                    val ci = line.indexOf(':')
                    if (ci > 0) {
                        val name = line.substring(0, ci).trim()
                        val value = line.substring(ci + 1).trim()
                        if (name.isNotEmpty()) hdrMap[name] = value
                    }
                }
                val cfr = try {
                    com.iptv.tvplayer.script.ChromeFetchProxy.fetch(
                        context, target, method, hdrMap,
                        if (reqBody.isNotEmpty()) reqBody else null,
                        (timeoutSec * 1000L).coerceAtLeast(8000L)
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "ChromeFetchProxy error: $t")
                    null
                }
                if (cfr != null && cfr.error == null && cfr.status in 100..599) {
                    val raw = StringBuilder()
                    raw.append("HTTP/1.1 ").append(cfr.status).append("\r\n")
                    for ((k, v) in cfr.headers) raw.append(k).append(": ").append(v).append("\r\n")
                    Log.d(TAG, "phpfetch(CFE): $method $target -> ${cfr.status} (${cfr.body.size}b)")
                    return respond(cfr.status, b64(raw.toString().take(8192)), "", target, cfr.body)
                }
                if (!com.iptv.tvplayer.script.ChromeFetchProxy.isPermanentlyUnavailable) {
                    // 暂态失败 (页面未就绪/瞬时错误): 硬失败 (x-up-status=0 -> curl errno 7 -> 脚本 die
                    // 且不写缓存), 上层播放器会重试, 代理就绪后自然成功;
                    // 绝不能回退 OkHttp —— 会拿到被 CDN 拒绝的诱饵 token 并毒化 30 分钟缓存
                    Log.w(TAG, "phpfetch(CFE) not ready for $target (${cfr?.error ?: "null"}) -> hard-fail")
                    return respond(0, "", b64("chrome proxy not ready"), target, ByteArray(0))
                }
                Log.w(TAG, "phpfetch(CFE) permanently unavailable for $target, fallback OkHttp")
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(follow)
                .followSslRedirects(follow)
                .build()

            val rb = okhttp3.Request.Builder().url(target)
            var hasUa = false
            String(hdrBytes, Charsets.ISO_8859_1).split("\r\n", "\n").forEach { line ->
                val ci = line.indexOf(':')
                if (ci > 0) {
                    val name = line.substring(0, ci).trim()
                    val value = line.substring(ci + 1).trim()
                    if (name.isNotEmpty()) {
                        try { rb.header(name, value) } catch (ignored: Exception) {}
                        if (name.equals("User-Agent", ignoreCase = true)) hasUa = true
                    }
                }
            }
            if (!hasUa) {
                rb.header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0 Mobile Safari/537.36")
            }
            val body = if (method == "POST" || method == "PUT" || method == "PATCH") {
                okhttp3.RequestBody.create(null as okhttp3.MediaType?, reqBody)
            } else null
            rb.method(method, body)

            try {
                client.newCall(rb.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    Log.d(TAG, "phpfetch: $method $target -> ${resp.code} (${bytes.size}b)")
                    val raw = StringBuilder()
                    raw.append("HTTP/1.1 ").append(resp.code).append(' ').append(resp.message ?: "").append("\r\n")
                    for ((k, v) in resp.headers) raw.append(k).append(": ").append(v).append("\r\n")
                    respond(resp.code, b64(raw.toString().take(8192)), "", resp.request.url.toString(), bytes)
                }
            } catch (e: Exception) {
                Log.d(TAG, "phpfetch: $method $target -> ERR ${e.message}")
                respond(0, "", b64(e.message ?: e.javaClass.simpleName), "", ByteArray(0))
            }
        } catch (e: Exception) {
            respond(0, "", b64(e.message ?: "phpfetch failed"), "", ByteArray(0))
        }
    }

    companion object {
        private const val TAG = "LocalServer"
    }
}




