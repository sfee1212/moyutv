package com.iptv.tvplayer.proxy

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 本地 TCP 隧道: 监听 127.0.0.1 随机端口, 每个连入的连接经 SOCKS5(或 HTTP CONNECT) 代理
 * 拨到目标地址后双向透传, 并做 RTSP 层改写。
 *
 * 用途: FFmpeg/MPV 的 RTSP 客户端不支持代理。对 "rtsp://host/path&socks5=ip:port"
 * 这类 TVBox 地址, 由本隧道代理 TCP 控制连接, 再把 URL 改写成 rtsp://127.0.0.1:port/path
 * 交给 MPV(配合 demuxer-lavf-o=rtsp_flags=prefer_tcp, RTP 走 interleaved-over-TCP)。
 *
 * RTSP 改写(运营商 PLTV 常见 302 重定向到另一台媒体服务器):
 *  - 下行: 3xx 响应的 Location: rtsp://真实主机/... 改写为 rtsp://127.0.0.1:本机口/...,
 *    并把 Location 里的主机记为 pendingTarget —— ffmpeg 随后重连本机口时, 隧道
 *    自动经代理拨到重定向后的新主机(否则 ffmpeg 会直连而绕过代理, 必然失败)。
 *  - 上行: 请求行/Host 中的 "127.0.0.1:本机口" 还原为当前目标主机(服务器常校验请求 URI)。
 *  - 消息外的 RTP interleaved('$' 帧)与消息体(SDP)按字节原样透传。
 */
class Socks5TcpRelay(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val httpConnect: Boolean,
    private val targetHost: String,
    private val targetPort: Int
) {
    companion object {
        private const val TAG = "Socks5Relay"
    }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    var localPort: Int = -1
        private set

    /** 302 重定向后优先使用的新目标 */
    private val pendingLock = Any()
    private var pendingHost: String? = null
    private var pendingPort: Int = 0

    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", 0))
        server = ss
        running = true
        localPort = ss.localPort
        Thread({
            Log.i(TAG, "隧道就绪 127.0.0.1:$localPort -> [$proxyHost:$proxyPort ${if (httpConnect) "HTTP-CONNECT" else "SOCKS5"}] -> $targetHost:$targetPort")
            while (running) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                Thread({ handle(client) }, "rtsp-relay-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "rtsp-relay-accept").apply {
            isDaemon = true
            start()
        }
        return localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            // 选取目标: 若此前的 302 已记录新目标, 本连接(ffmpeg 重定向后的重连)用它
            var th = targetHost
            var tp = targetPort
            synchronized(pendingLock) {
                val ph = pendingHost
                if (ph != null) {
                    th = ph
                    tp = pendingPort
                    pendingHost = null
                    Log.i(TAG, "跟随 302 重定向 -> $th:$tp")
                }
            }
            upstream = Socket().also {
                it.tcpNoDelay = true
                it.connect(InetSocketAddress(proxyHost, proxyPort), 8000)
            }
            if (httpConnect) httpConnect(upstream, th, tp) else socks5Connect(upstream, th, tp)

            val port = localPort
            val up = upstream
            val upRw = RtspRewriter(upstream = true, tHost = th, tPort = tp, localPort = port, relay = this)
            val downRw = RtspRewriter(upstream = false, tHost = th, tPort = tp, localPort = port, relay = this)
            Thread({ pumpTransformed(client.getInputStream(), up.getOutputStream(), upRw) }, "rtsp-relay-up").apply {
                isDaemon = true
                start()
            }
            pumpTransformed(up.getInputStream(), client.getOutputStream(), downRw)
        } catch (e: Exception) {
            Log.w(TAG, "隧道连接失败: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
        }
    }

    private fun setPending(h: String, p: Int) {
        synchronized(pendingLock) {
            pendingHost = h
            pendingPort = p
        }
    }

    /** SOCKS5 握手 + CONNECT (无认证; IPv4 用 ATYP=1, 其余用域名 ATYP=3) */
    private fun socks5Connect(s: Socket, th: String, tp: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))   // VER=5, NMETHODS=1, NO_AUTH
        out.flush()
        val hs = readExact(inp, 2)
        if ((hs[0].toInt() and 0xFF) != 5 || (hs[1].toInt() and 0xFF) != 0x00) {
            throw java.io.IOException("SOCKS5 协商失败(method=${hs[1]})")
        }
        val host = th.toByteArray(Charsets.ISO_8859_1)
        if (host.size > 255) throw java.io.IOException("目标域名过长")
        val ipBytes = try {
            if (th.count { it == '.' } == 3) java.net.InetAddress.getByName(th).address else null
        } catch (e: Exception) { null }
        val req = if (ipBytes != null && ipBytes.size == 4) {
            val r = ByteArray(10)
            r[0] = 0x05; r[1] = 0x01; r[2] = 0x00; r[3] = 0x01
            System.arraycopy(ipBytes, 0, r, 4, 4)
            r[8] = ((tp shr 8) and 0xFF).toByte()
            r[9] = (tp and 0xFF).toByte()
            r
        } else {
            val r = ByteArray(7 + host.size)
            r[0] = 0x05
            r[1] = 0x01    // CONNECT
            r[2] = 0x00    // RSV
            r[3] = 0x03    // ATYP=DOMAINNAME
            r[4] = host.size.toByte()
            System.arraycopy(host, 0, r, 5, host.size)
            r[5 + host.size] = ((tp shr 8) and 0xFF).toByte()
            r[6 + host.size] = (tp and 0xFF).toByte()
            r
        }
        out.write(req)
        out.flush()
        val rep = readExact(inp, 4)
        if ((rep[1].toInt() and 0xFF) != 0x00) {
            throw java.io.IOException("SOCKS5 CONNECT 失败 code=${rep[1].toInt() and 0xFF}")
        }
        // 丢弃绑定地址 VER+REP+RSV+ATYP 之外的部分: addr + 2字节port
        when ((rep[3].toInt() and 0xFF)) {
            1 -> readExact(inp, 6)               // IPv4(4) + port(2)
            4 -> readExact(inp, 18)              // IPv6(16) + port(2)
            3 -> { val l = readExact(inp, 1)[0].toInt() and 0xFF; readExact(inp, l + 2) }
            else -> {}
        }
    }

    /** HTTP 代理 CONNECT 握手 */
    private fun httpConnect(s: Socket, th: String, tp: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val req = "CONNECT $th:$tp HTTP/1.1\r\nHost: $th:$tp\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw java.io.IOException("HTTP 代理无响应")
            sb.append(b.toChar())
            if (sb.length > 8192) throw java.io.IOException("HTTP 代理响应异常")
        }
        val status = sb.lineSequence().firstOrNull() ?: ""
        if (!status.contains(" 200")) throw java.io.IOException("HTTP CONNECT 失败: $status")
    }

    private fun readExact(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw java.io.IOException("代理连接被关闭")
            off += r
        }
        return buf
    }

    private fun pumpTransformed(inp: InputStream, out: OutputStream, rw: RtspRewriter) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val r = inp.read(buf)
                if (r < 0) break
                val bytes = rw.feed(buf, r)
                if (bytes != null && bytes.isNotEmpty()) {
                    out.write(bytes)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { out.close() } catch (_: Exception) {}
            try { inp.close() } catch (_: Exception) {}
        }
    }

    /**
     * RTSP 消息改写状态机(每个方向一个实例, 仅被对应 pump 线程调用):
     *  按 "RTSP 头(至 \r\n\r\n) + Content-Length 体 + '$' interleaved 帧" 切分字节流,
     *  头部文本做方向相关的字符串改写, 其余原样透传; 头异常超长则放弃解析降级为裸透传。
     */
    private class RtspRewriter(
        private val upstream: Boolean,
        private val tHost: String,
        private val tPort: Int,
        private val localPort: Int,
        private val relay: Socks5TcpRelay
    ) {
        private val acc = ByteArray(128 * 1024)
        private var accLen = 0
        private var bodyLeft = 0
        private var rawMode = false
        private val localAuth = "127.0.0.1:$localPort"

        fun feed(data: ByteArray, len: Int): ByteArray? {
            if (rawMode) return if (len > 0) data.copyOf(len) else null
            if (len > 0) {
                if (accLen + len > acc.size) {
                    val o = ByteArrayOutputStream()
                    o.write(acc, 0, accLen)
                    o.write(data, 0, len)
                    accLen = 0
                    rawMode = true
                    return o.toByteArray()
                }
                System.arraycopy(data, 0, acc, accLen, len)
                accLen += len
            }
            val out = ByteArrayOutputStream()
            while (accLen > 0) {
                if (bodyLeft > 0) {
                    val n = bodyLeft.coerceAtMost(accLen)
                    out.write(acc, 0, n)
                    consume(n)
                    bodyLeft -= n
                    continue
                }
                if (acc[0] == 0x24.toByte()) {          // RTP interleaved '$' 帧
                    if (accLen < 4) break
                    val flen = 4 + ((acc[2].toInt() and 0xFF) shl 8) + (acc[3].toInt() and 0xFF)
                    if (flen < 4 || accLen < flen) break
                    out.write(acc, 0, flen)
                    consume(flen)
                    continue
                }
                val idx = indexOfHeaderEnd()
                if (idx < 0) {
                    if (accLen >= 16384) {              // 异常头, 放弃解析
                        out.write(acc, 0, accLen)
                        accLen = 0
                        rawMode = true
                    }
                    break
                }
                val hlen = idx + 4
                val header = String(acc, 0, hlen, Charsets.ISO_8859_1)
                val newHeader = transform(header)
                out.write(newHeader.toByteArray(Charsets.ISO_8859_1))
                consume(hlen)
                bodyLeft = parseContentLength(newHeader)
            }
            return if (out.size() > 0) out.toByteArray() else null
        }

        private fun transform(header: String): String {
            if (upstream) {
                // ffmpeg 侧请求: 本机地址还原为真实目标(请求行 + Host 头)
                return header.replace(localAuth, "$tHost:$tPort")
            }
            // 下行: 仅改写 3xx 响应的 Location
            if (!header.startsWith("RTSP/")) return header
            val firstLine = header.substringBefore("\r\n")
            val code = firstLine.split(' ', '\t').getOrNull(1)?.toIntOrNull() ?: return header
            if (code !in 300..399) return header
            val lines = header.split("\r\n").toMutableList()
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("location:", ignoreCase = true)) continue
                val value = line.substring(line.indexOf(':') + 1).trim()
                val vi = value.indexOf("rtsp://", ignoreCase = true)
                if (vi < 0) continue
                val urlPart = value.substring(vi)
                val authStart = 7                                     // "rtsp://".length
                val slash = urlPart.indexOf('/', authStart)
                val quest = urlPart.indexOf('?', authStart)
                val authEnd = listOf(slash, quest).filter { it >= 0 }.minOrNull() ?: urlPart.length
                val authority = urlPart.substring(authStart, authEnd)
                val rest = urlPart.substring(authEnd)
                val ah = authority.substringAfter('@')
                val h: String
                val p: Int
                if (ah.startsWith("[")) {                             // [v6]:port
                    h = ah.substringBefore(']')
                    p = ah.substringAfter("]:", "554").toIntOrNull() ?: 554
                } else if (ah.contains(':')) {
                    h = ah.substringBefore(':')
                    p = ah.substringAfter(':').toIntOrNull() ?: 554
                } else {
                    h = ah
                    p = 554
                }
                if (h.isEmpty()) continue
                relay.setPending(h, p)
                Log.i(TAG, "302 Location: ${h}:$p -> 改写为 127.0.0.1:$localPort")
                val colon = line.indexOf(':')
                lines[i] = line.substring(0, colon + 1) + " rtsp://$localAuth$rest"
                break
            }
            return lines.joinToString("\r\n")
        }

        private fun parseContentLength(header: String): Int {
            header.lineSequence().forEach { l ->
                if (l.startsWith("content-length", ignoreCase = true)) {
                    return l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            return 0
        }

        private fun indexOfHeaderEnd(): Int {
            var i = 0
            while (i + 3 < accLen) {
                if (acc[i] == 0x0D.toByte() && acc[i + 1] == 0x0A.toByte() &&
                    acc[i + 2] == 0x0D.toByte() && acc[i + 3] == 0x0A.toByte()) return i
                i++
            }
            return -1
        }

        private fun consume(n: Int) {
            if (n >= accLen) {
                accLen = 0
                return
            }
            System.arraycopy(acc, n, acc, 0, accLen - n)
            accLen -= n
        }
    }
}
