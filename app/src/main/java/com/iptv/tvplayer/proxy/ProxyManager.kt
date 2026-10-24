package com.iptv.tvplayer.proxy

import android.content.Context
import android.util.Log

object ProxyManager {
    private const val TAG = "ProxyManager"
    private var forceTVProxy: ForceTVProxy? = null
    private var socksProxyServer: SocksProxyServer? = null
    private var isSocksProxyStarted = false

    fun init(context: Context) {
        forceTVProxy = ForceTVProxy(context.applicationContext)
    }

    fun resolveUrl(url: String): String {
        Log.d(TAG, "Resolving URL: $url")
        if (url.startsWith("p2p://") || url.startsWith("p9p://") || url.startsWith("p3p://") || url.startsWith("forcetv://") || url.startsWith("mitv://")) {
            return forceTVProxy?.fetch(url) ?: url
        }
        if (url.startsWith("tvbus://")) {
            // TODO: Initialize TVBusProxy and resolve URL
            return url
        }
        
        val proxyStr = MihomoProxyManager.proxyAddress
        if (proxyStr.isNotEmpty()) {
            // SOCKS5 is now natively handled by ExoPlayer (via OkHttp) and MPV directly.
            // We no longer need to wrap URLs with SocksProxyServer.
        }
        
        return url
    }

    /**
     * 识别直播地址中内嵌的代理参数，并转换为播放器(NativePlayerManager)已支持的前缀格式。
     *
     * socks5 代理格式:
     *   http://target.m3u8?q=1&socks5://host:port  -> socks5://host:port/http://target.m3u8?q=1
     *   http://target.m3u8?socks5://host:port      -> socks5://host:port/http://target.m3u8
     *   http://target.m3u8?q=1&socks5=host:port    -> socks5://host:port/http://target.m3u8?q=1
     *   http://target.m3u8?socks5=host:port        -> socks5://host:port/http://target.m3u8
     *   sk://ip:port&http://target.m3u8            -> 播放器原生支持，保持不变
     *   socks5://host:port/http://target.m3u8      -> 播放器原生支持，保持不变
     *
     * http 代理格式:
     *   http://target.m3u8&http_proxy=host:port    -> proxy://host:port/http://target.m3u8
     *   http://target.m3u8?http_proxy=host:port    -> proxy://host:port/http://target.m3u8
     *   http://target.m3u8&http_proxy://host:port  -> proxy://host:port/http://target.m3u8
     *   http://target.m3u8?http_proxy://host:port  -> proxy://host:port/http://target.m3u8
     *
     * 未命中代理特征的地址原样返回；识别失败(如 host:port 不合法)时也保持原样，避免影响正常播放。
     */
    fun normalizeProxyUrl(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return url
        val lower = raw.lowercase(java.util.Locale.US)

        // 仅处理 http/https 地址中内嵌代理参数的情况；前缀格式本身已由播放器原生支持
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return url

        // 代理参数标记 -> 转换后的前缀 scheme ("proxy" 在播放器内会映射为 http 代理)
        val markers = listOf(
            "socks5://" to "socks5",
            "socks5=" to "socks5",
            "http_proxy://" to "proxy",
            "http_proxy=" to "proxy"
        )

        for ((marker, scheme) in markers) {
            var from = 0
            while (true) {
                val idx = lower.indexOf(marker, from)
                if (idx < 0) break
                from = idx + 1

                // 代理参数标记必须紧跟查询分隔符 ? 或 & 之后才算内嵌代理
                if (idx == 0) continue
                val prev = lower[idx - 1]
                if (prev != '?' && prev != '&') continue

                // 代理参数取到下一个 & 为止 (如 socks5://host:port / http_proxy=host:port)
                val rest = raw.substring(idx)
                val amp = rest.indexOf('&')
                val proxySpec = if (amp >= 0) rest.substring(0, amp) else rest
                if (!proxySpec.startsWith(marker, ignoreCase = true)) continue
                val hostPort = proxySpec.substring(marker.length)
                if (!isValidHostPort(hostPort)) return url

                // 剔除代理参数后的真实播放地址
                val head = raw.substring(0, idx).trimEnd('?', '&')
                val tail = if (amp >= 0) raw.substring(idx + amp + 1) else ""
                val target = if (tail.isEmpty()) head
                    else head + (if (head.contains("?")) "&" else "?") + tail
                if (!target.startsWith("http://", ignoreCase = true) &&
                    !target.startsWith("https://", ignoreCase = true)) return url

                // 转换为播放器已支持的前缀格式: scheme://host:port/http://target
                return "$scheme://$hostPort/$target"
            }
        }
        return url
    }

    /** 校验代理地址必须为合法的 host:port 形式 */
    private fun isValidHostPort(s: String): Boolean {
        if (s.isEmpty() || s.contains('/') || s.contains('?') || s.contains('&')) return false
        val parts = s.split(":")
        if (parts.size != 2 || parts[0].isEmpty()) return false
        val port = parts[1].toIntOrNull() ?: return false
        return port in 1..65535
    }

    /** TVBox 风格内嵌代理注释解析结果 */
    data class ProxyAnnotation(
        val cleanUrl: String,
        val proxyHost: String = "",
        val proxyPort: Int = 0,
        val httpProxy: Boolean = false     // true=HTTP 代理(CONNECT), false=SOCKS5
    ) {
        val hasProxy: Boolean get() = proxyPort in 1..65535
    }

    /**
     * 解析 TVBox 风格内嵌代理注释(任意协议地址):
     *   rtsp://host/path.smil&socks5=ip:port
     *   http://target.m3u8?socks5://ip:port
     *   http://target.m3u8&proxy=http://ip:port
     *   http://target.m3u8&http_proxy=ip:port
     * 未命中注释时原样返回; 注释不合法(如 host:port 非法)时仅去注释不带代理, 避免污染播放地址。
     */
    fun parseProxyAnnotation(url: String): ProxyAnnotation {
        val raw = url.trim()
        if (raw.isEmpty()) return ProxyAnnotation(url)
        val lower = raw.lowercase(java.util.Locale.US)

        val markers = listOf(
            "socks5://" to false, "socks5=" to false,
            "http_proxy://" to true, "http_proxy=" to true,
            "proxy://" to false, "proxy=" to false
        )

        for ((marker, isHttpMarker) in markers) {
            var from = 0
            while (true) {
                val idx = lower.indexOf(marker, from)
                if (idx < 0) break
                from = idx + 1
                // 标记必须紧跟查询分隔符 ? 或 & 之后才算内嵌代理
                if (idx == 0) continue
                val prev = lower[idx - 1]
                if (prev != '?' && prev != '&') continue

                // 代理参数取到下一个 & 为止
                val rest = raw.substring(idx)
                val amp = rest.indexOf('&')
                var spec = if (amp >= 0) rest.substring(0, amp) else rest
                if (!spec.startsWith(marker, ignoreCase = true)) continue
                spec = spec.substring(marker.length).trim()
                // proxy=http://host:port 形式再剥一层 scheme
                var httpProxy = isHttpMarker
                if (spec.startsWith("socks5://", ignoreCase = true)) {
                    spec = spec.substring("socks5://".length)
                } else if (spec.startsWith("http://", ignoreCase = true)) {
                    spec = spec.substring("http://".length)
                    httpProxy = true
                }
                // 剔除注释后的播放地址
                val head = raw.substring(0, idx).trimEnd('?', '&')
                val tail = if (amp >= 0) raw.substring(idx + amp + 1) else ""
                val target = if (tail.isEmpty()) head
                    else head + (if (head.contains("?")) "&" else "?") + tail
                if (!isValidHostPort(spec)) return ProxyAnnotation(target)
                val parts = spec.split(":")
                return ProxyAnnotation(target, parts[0], parts[1].toInt(), httpProxy)
            }
        }
        return ProxyAnnotation(raw)
    }
}
