package com.iptv.tvplayer.proxy

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 弱网平滑缓冲(抖动吸收器)。
 *
 * 背景线程在客户端消费的同时提前向上游读取, 维持最多 [maxBytes] 字节的预取缓冲:
 *  - 网络突发到达时先落入缓冲, 不受客户端读取节奏影响;
 *  - 网络短暂抖动/断流时由缓冲继续供数, 播放端不感知;
 *  - 缓冲写满则对上游回压(与无预取时相同的 TCP 背压), 内存占用有界;
 *  - 客户端断开时 NanoHTTPD 会 close() 本流, 读取线程随上游流关闭立即退出, 不泄漏。
 *
 * 读取语义与原 InputStream 一致: 阻塞直到有数据、EOF 返回 -1、上游错误在缓冲耗尽后抛出。
 */
class PrefetchInputStream(
    private val source: InputStream,
    private val maxBytes: Int = 512 * 1024,
    private val chunkSize: Int = 32 * 1024
) : InputStream() {

    private companion object {
        private val EOF_SENTINEL = Any()
    }

    // 已预取但尚未被客户端消费的字节数(近似, 用于背压)
    private val buffered = AtomicInteger(0)
    // 背压等待锁: 消费方在锁内减计数并 notify, 预取线程在锁内检查并 wait —— 无丢失唤醒, 也无轮询周期限速
    private val bufLock = Object()
    private val queue = ArrayBlockingQueue<Any>(maxBytes / chunkSize + 4)
    private val pool = ConcurrentLinkedQueue<ByteArray>()

    @Volatile private var closed = false
    @Volatile private var readError: IOException? = null

    // 仅单消费线程访问
    private var current: ByteArray? = null
    private var pos = 0
    private var eof = false
    private val singleBuf = ByteArray(1)

    init {
        Thread({
            try {
                while (!closed) {
                    // 背压: 缓冲达到上限则等待客户端消费(wait 由 read() 的 notify 唤醒, 立即恢复灌入)
                    synchronized(bufLock) {
                        while (buffered.get() >= maxBytes && !closed) {
                            bufLock.wait()
                        }
                    }
                    if (closed) break
                    val buf = pool.poll() ?: ByteArray(chunkSize)
                    val n = try {
                        source.read(buf)
                    } catch (e: Exception) {
                        if (!closed) readError = e as? IOException ?: IOException(e)
                        break
                    }
                    if (n < 0) break
                    if (n == 0) { pool.offer(buf); continue }
                    // 只有整块数组才回池, 防止块尺寸逐步退化
                    val chunk = if (n == buf.size) {
                        buf
                    } else {
                        val c = buf.copyOf(n)
                        pool.offer(buf)
                        c
                    }
                    buffered.addAndGet(chunk.size)
                    var offered = false
                    while (!offered && !closed) {
                        offered = queue.offer(chunk, 20, TimeUnit.MILLISECONDS)
                    }
                    if (!offered) { // closed: 归还内存并退出
                        buffered.addAndGet(-chunk.size)
                        if (chunk.size == chunkSize) pool.offer(chunk)
                        break
                    }
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                // EOF/错误哨兵: 单次限时投递, 失败也直接结束线程
                try { queue.offer(EOF_SENTINEL, 100, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                try { source.close() } catch (_: Exception) {}
            }
        }, "relay-prefetch").apply {
            isDaemon = true
            start()
        }
    }

    /** 取下一块数据到 current; 返回 false 表示 EOF(或已关闭), 有错误则抛出。 */
    private fun fill(): Boolean {
        val cur = current
        if (cur != null && pos < cur.size) return true
        current = null
        pos = 0
        if (eof || closed) return false
        while (true) {
            if (closed) { eof = true; return false }
            val item = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt(); eof = true; return false
            }
            if (item == null) {
                // 读取线程已死且错误已记录、队列见底 -> 抛错
                val err = readError
                if (err != null && queue.isEmpty()) { eof = true; throw err }
                continue
            }
            if (item === EOF_SENTINEL) {
                eof = true
                val err = readError
                if (err != null) throw err
                return false
            }
            @Suppress("UNCHECKED_CAST")
            current = item as ByteArray
            pos = 0
            return true
        }
    }

    override fun read(): Int {
        return if (read(singleBuf, 0, 1) == 1) singleBuf[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val cur = current!!
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        synchronized(bufLock) {
            buffered.addAndGet(-n)
            bufLock.notifyAll()
        }
        if (pos >= cur.size) {
            if (cur.size == chunkSize) pool.offer(cur)
            current = null
            pos = 0
        }
        return n
    }

    override fun available(): Int = buffered.get().coerceIn(0, Int.MAX_VALUE)

    override fun close() {
        if (closed) return
        synchronized(bufLock) {
            closed = true
            bufLock.notifyAll()
        }
        // 关闭上游流使读取线程的 source.read() 立即异常退出
        try { source.close() } catch (_: Exception) {}
    }
}
