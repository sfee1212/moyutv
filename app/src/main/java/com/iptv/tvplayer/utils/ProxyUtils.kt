package com.iptv.tvplayer.utils

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.net.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object ProxyUtils {
    suspend fun filterValidSocks5Ips(ips: List<String>): List<String> = coroutineScope {
        ips.map { ipStr ->
            async(Dispatchers.IO) {
                try {
                    val parts = ipStr.split(":")
                    if (parts.size != 2) return@async null
                    
                    // Simple TCP connect test (supports both SOCKS5 and HTTP proxies)
                    val socket = Socket()
                    socket.connect(InetSocketAddress(parts[0], parts[1].toInt()), 3000)
                    socket.close()
                    
                    ipStr
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private val proxyTypeCache = ConcurrentHashMap<String, String>()

    fun detectProxyType(host: String, port: Int, defaultType: String): String {
        val key = "$host:$port"
        proxyTypeCache[key]?.let { return it }

        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2000)
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            out.write("GET http://www.baidu.com/ HTTP/1.1\r\nHost: www.baidu.com\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val inputStream = socket.getInputStream()
            val buffer = ByteArray(12)
            val read = inputStream.read(buffer)
            socket.close()
            val isHttp = read > 0 && String(buffer, 0, read).uppercase().startsWith("HTTP/")
            val type = if (isHttp) "http" else "socks"
            proxyTypeCache[key] = type
            type
        } catch (e: Exception) {
            proxyTypeCache[key] = defaultType
            defaultType
        }
    }
}
