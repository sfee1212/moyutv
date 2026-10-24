package com.iptv.tvplayer.script

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * ssl:// 套接字回环隧道 (为 assets/php/https_wrapper.php 的 stream_socket_client/fsockopen 垫片服务)
 *
 * 内置 php 二进制未编译 openssl 扩展, 没有 "ssl" 套接字传输层, 脚本的
 * stream_socket_client('ssl://host:port') (WebSocket 心跳等长连接) 原生必失败。
 * https_wrapper.php 的垫片经二进制补丁接管这两个函数后, ssl/tls 方案连到本服务:
 *
 *   PHP 侧发送一行:  UPSTREAM <host> <port>\n
 *   服务端对远端做真实 TLS 握手 (SSLSocketFactory), 成功回 "OK\n", 之后把回环明文
 *   字节与远端 TLS 字节双向泵送, 直到任一侧关闭或 60s 空闲; 失败回 "ERR <原因>\n"。
 *
 * 仅绑定 127.0.0.1, 不对局域网开放。随首个脚本执行请求懒启动, 进程结束自动回收。
 */
object UpstreamTunnel {
    private const val TAG = "UpstreamTunnel"
    const val PORT = 8081
    private const val MAX_ACTIVE = 32
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    // 预算与 PHP 侧握手窗口对齐 (垫片 fgets 等 5s): 远端 TCP+TLS 必须快速成败,
    // 失败时 ERR 行要在 PHP 放弃前送达, 不留静默
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val TLS_TIMEOUT_MS = 3000
    private const val IDLE_TIMEOUT_MS = 60_000

    @Volatile private var started = false
    private val lock = Any()
    private val active = AtomicInteger(0)

    /** 幂等启动 (首个解析脚本执行时调用) */
    fun ensureStarted() {
        if (started) return
        synchronized(lock) {
            if (started) return
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", PORT))
                Thread({ acceptLoop(ss) }, "upstream-tunnel-accept").apply {
                    isDaemon = true
                    start()
                }
                started = true
                Log.i(TAG, "started on 127.0.0.1:$PORT")
            } catch (t: Throwable) {
                Log.w(TAG, "start failed: $t")
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val client = try {
                ss.accept()
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed: $t")
                break
            }
            Thread({ handle(client) }, "upstream-tunnel").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** 读一行 (只消费到 \n, 不预读后续字节 — 避免吞掉隧道数据) */
    private fun readLine(`in`: InputStream): String? {
        val sb = StringBuilder()
        val b = ByteArray(1)
        while (sb.length < 256) {
            val n = `in`.read(b)
            if (n <= 0) return if (sb.isEmpty()) null else sb.toString()
            val c = b[0].toChar()
            if (c == '\n') return sb.toString()
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }

    private fun handle(client: Socket) {
        var counted = false
        var raw: Socket? = null
        var target = "?"
        val t0 = System.currentTimeMillis()
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val line = readLine(client.getInputStream()) ?: throw java.io.IOException("no handshake")
            if (!line.startsWith("UPSTREAM ")) throw java.io.IOException("bad handshake")
            val parts = line.split(' ')
            if (parts.size != 3) throw java.io.IOException("bad upstream address")
            val host = parts[1].removeSurrounding("[", "]")
            val port = parts[2].toIntOrNull() ?: throw java.io.IOException("bad port")
            if (host.isEmpty() || host.any { it < '!' || it > '~' } || port !in 1..65535) {
                throw java.io.IOException("bad upstream address")
            }
            target = "$host:$port"
            if (active.incrementAndGet() > MAX_ACTIVE) {
                active.decrementAndGet()
                throw java.io.IOException("too many tunnels")
            }
            counted = true

            val sock = Socket()
            raw = sock
            val tc = System.currentTimeMillis()
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                throw java.io.IOException("tcp ${System.currentTimeMillis() - tc}ms: ${e.message}")
            }
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(sock, host, port, true) as SSLSocket
            val th = System.currentTimeMillis()
            try {
                tls.soTimeout = TLS_TIMEOUT_MS
                tls.startHandshake()
            } catch (e: Exception) {
                throw java.io.IOException("tls ${System.currentTimeMillis() - th}ms: ${e.message}")
            }
            Log.d(TAG, "upstream $target ready in ${System.currentTimeMillis() - t0}ms")

            // TLS 握手完成 — 先回 OK 再启动泵, 保证 PHP 侧 fgets 读完 OK 行后远端字节才流入
            client.soTimeout = 0
            val out = client.getOutputStream()
            out.write("OK\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            pump(client, tls, sock)
            Log.d(TAG, "upstream $target closed after ${System.currentTimeMillis() - t0}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "upstream $target FAILED after ${System.currentTimeMillis() - t0}ms: ${t.message}")
            try {
                val msg = (t.message ?: t.javaClass.simpleName)
                    .replace('\n', ' ').replace('\r', ' ').take(200)
                client.getOutputStream().write("ERR $msg\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
            } catch (_: Exception) {
            }
        } finally {
            if (counted) active.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    /** 双向泵: 回环明文 <-> 远端 TLS; 任一侧 EOF/异常/空闲超时即整体关闭 */
    private fun pump(client: Socket, remote: SSLSocket, raw: Socket) {
        val closed = AtomicBoolean(false)
        val closeAll = {
            if (closed.compareAndSet(false, true)) {
                try { client.close() } catch (_: Exception) {}
                try { raw.close() } catch (_: Exception) {}
            }
            Unit
        }
        try { client.soTimeout = IDLE_TIMEOUT_MS } catch (_: Exception) {}
        try { remote.soTimeout = IDLE_TIMEOUT_MS } catch (_: Exception) {}
        val t1 = Thread({ copy(client.getInputStream(), remote.getOutputStream(), closeAll) }, "tunnel-c2r").apply {
            isDaemon = true; start()
        }
        val t2 = Thread({ copy(remote.getInputStream(), client.getOutputStream(), closeAll) }, "tunnel-r2c").apply {
            isDaemon = true; start()
        }
        t1.join()
        closeAll()
        try { t2.join(2000) } catch (_: Exception) {}
    }

    private fun copy(input: InputStream, output: OutputStream, closeAll: () -> Unit) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
        }
        closeAll()
    }
}
