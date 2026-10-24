package com.iptv.tvplayer.proxy

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * rtp:// / udp:// 组播直播 → 连续 MPEG-TS 字节流。
 *
 * Media3/ExoPlayer 没有 UDP/RTP 数据源, 本类在 LocalServer /rtpts 路由里被打开:
 * 加入组播组(或绑定单播端口)收 UDP 报文, 剥 RTP 头(12B+CSRC+扩展, RFC2250 '$' 对齐封装),
 * 整理成 TS 后经 HTTP video/mp2t 回流给 EXO 播放。
 * 仅处理 IPTV 常见形态: RTP(v2) 载荷为 N*188 裸 TS 或 N*(4+188) '$' 封装; udp:// 为裸 TS 报文。
 */
class RtpTsInputStream(
    context: Context,
    private val target: String
) : InputStream() {

    companion object {
        private const val TAG = "RtpTsProxy"
        private const val TS_PKT = 188

        fun isRtpOrUdpUrl(url: String): Boolean {
            val l = url.trim().lowercase(java.util.Locale.US)
            return l.startsWith("rtp://") || l.startsWith("udp://")
        }

        /** 生成 LocalServer 回流地址; 非 rtp/udp 返回 null */
        fun toLocalUrl(url: String): String? {
            if (!isRtpOrUdpUrl(url)) return null
            val enc = java.net.URLEncoder.encode(url.trim(), "UTF-8")
            return "http://127.0.0.1:8080/rtpts?url=$enc"
        }
    }

    private val isRtp: Boolean
    private val socket: DatagramSocket          // MulticastSocket 是其子类
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var closed = false
    private var timeouts = 0

    private val recvBuf = ByteArray(65536)
    private val stageBuf = ByteArray(65536)      // '$' 封装重排用
    // 当前待消费视图: bufArr[bufPos until bufPos+bufLen)
    private var bufArr: ByteArray = recvBuf
    private var bufPos = 0
    private var bufLen = 0

    init {
        val t = target.trim()
        isRtp = t.lowercase(java.util.Locale.US).startsWith("rtp://")
        val body = t.substringAfter("://")
        val hp = body.substringBefore('/')
        val hostStr = hp.substringBefore(':')
        val port = hp.substringAfter(':', "").toIntOrNull() ?: -1
        if (hostStr.isEmpty() || port !in 1..65535) throw java.io.IOException("非法地址: $target")
        val addr = InetAddress.getByName(hostStr)
        socket = if (addr.isMulticastAddress) {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(port))
            ms.receiveBufferSize = 4 * 1024 * 1024
            @Suppress("DEPRECATION")
            ms.joinGroup(addr)          // 默认网卡加入; IPTV 盒子一般单网卡
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wm.createMulticastLock("moyu-rtp")
                lock.setReferenceCounted(false)
                lock.acquire()
                multicastLock = lock
            } catch (e: Exception) {
                Log.w(TAG, "MulticastLock 获取失败(WiFi下可能收不到组播): $e")
            }
            Log.i(TAG, "加入组播 $hostStr:$port")
            ms
        } else {
            // 单播 RTP: 绑定同端口被动接收
            val ds = DatagramSocket(null)
            ds.reuseAddress = true
            ds.bind(InetSocketAddress(port))
            ds.receiveBufferSize = 4 * 1024 * 1024
            Log.i(TAG, "单播监听 :$port ($target)")
            ds
        }
        socket.soTimeout = 8000
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (bufLen <= 0) {
            if (!fill()) return -1
        }
        val n = len.coerceAtMost(bufLen)
        System.arraycopy(bufArr, bufPos, b, off, n)
        bufPos += n
        bufLen -= n
        return n
    }

    override fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        super.close()
    }

    /** 收一个报文并解析出 TS 视图; 返回 false 表示流结束 */
    private fun fill(): Boolean {
        while (!closed) {
            val pkt = DatagramPacket(recvBuf, recvBuf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                if (++timeouts >= 4) {          // 约32s无数据, 给播放器一个 EOF 触发重连
                    Log.w(TAG, "无数据超时, 结束流: $target")
                    return false
                }
                continue
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "接收失败: $e")
                return false
            }
            timeouts = 0
            if (extract(pkt.data, pkt.length)) return true
        }
        return false
    }

    /** 从一个 UDP 报文提取连续 TS; 成功则填充视图返回 true, 无法识别返回 false(丢弃该包) */
    private fun extract(data: ByteArray, len: Int): Boolean {
        var end = len
        var off = 0
        if (isRtp) {
            if (end < 12) return false
            if (((data[0].toInt() shr 6) and 3) != 2) return false          // 非 RTP v2
            if ((data[0].toInt() and 0x20) != 0) {                          // padding bit
                val pad = data[end - 1].toInt() and 0xFF
                if (pad <= 0 || pad > end - 12) return false
                end -= pad
            }
            val cc = data[0].toInt() and 0x0F
            off = 12 + 4 * cc
            if (((data[0].toInt() shr 4) and 1) == 1) {                     // extension header
                if (off + 4 > end) return false
                val extWords = ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                off += 4 + 4 * extWords
            }
            if (off >= end) return false
        }
        val plen = end - off
        if (plen < TS_PKT) return false

        // A) 裸 N*188 TS (IPTV 最常见)
        if (plen % TS_PKT == 0 && data[off] == 0x47.toByte()) {
            bufArr = data; bufPos = off; bufLen = plen
            return true
        }
        // B) RFC2250 '$' 对齐封装: N*(4+188)
        if (plen % (TS_PKT + 4) == 0 && data[off] == 0x24.toByte() && plen >= TS_PKT + 4) {
            val cnt = plen / (TS_PKT + 4)
            var si = off
            var di = 0
            for (i in 0 until cnt) {
                if (data[si] != 0x24.toByte()) return false
                System.arraycopy(data, si + 4, stageBuf, di, TS_PKT)
                si += TS_PKT + 4
                di += TS_PKT
            }
            bufArr = stageBuf; bufPos = 0; bufLen = di
            return true
        }
        // C) 失步重同步: 前 188 字节内找 0x47 且与下一 TS 包对齐
        val maxOff = minOf(plen - TS_PKT, TS_PKT - 1)
        for (s in 0..maxOff) {
            if (data[off + s] != 0x47.toByte()) continue
            if (plen - s >= 2 * TS_PKT && data[off + s + TS_PKT] != 0x47.toByte()) continue
            val usable = ((plen - s) / TS_PKT) * TS_PKT
            if (usable >= TS_PKT) {
                bufArr = data; bufPos = off + s; bufLen = usable
                return true
            }
        }
        return false
    }
}
