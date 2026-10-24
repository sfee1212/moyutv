package com.iptv.tvplayer.script

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 通用 PHP 解释器桥接 (子进程方案)
 *
 * 解释器二进制 (静态编译 android-bionic, 带 curl 扩展) 查找顺序:
 *   1. /sdcard/摸鱼TV/bin/php          (用户可自行替换/覆盖)
 *   2. nativeLibraryDir/libphp.so      (随 APK 打包, 开箱即用)
 *   3. <filesDir>/bin/php
 *
 * 工作方式: 常驻 `php -S 127.0.0.1:<port> -t <脚本目录>` 子进程 (cli-server SAPI,
 * header()/Location/302 语义完整), 并注入 curl.cainfo(CA 包) 保证 https 校验可用。
 * LocalServer /phpout/ 路由反向代理过去。
 * 脚本挂死 → 代理读超时 → destroyForcibly 杀掉重建, 不影响播放器主进程。
 */
object PhpRuntimeBridge {
    private const val TAG = "PhpRuntimeBridge"
    private const val START_TIMEOUT_MS = 5000L
    private const val HTTP_TIMEOUT_SEC = 15L

    @Volatile private var process: Process? = null
    @Volatile private var port: Int = 0
    @Volatile private var scriptDirPath: String? = null
    private val lock = Any()

    fun execute(context: Context, scriptFile: File, query: String): PhpScriptManager.PhpResult {
        // ssl:// 回环隧道随脚本执行懒启动 (幂等; 脚本没用 ssl 时也无副作用)
        try { UpstreamTunnel.ensureStarted() } catch (_: Throwable) {}
        val docRoot = scriptFile.parentFile
            ?: return PhpScriptManager.PhpResult.Error(502, "脚本目录未知")
        val binaries = findBinaryCandidates(context)
        if (binaries.isEmpty()) {
            return PhpScriptManager.PhpResult.Error(
                502,
                "未找到 PHP 解释器。可放置位置: /sdcard/摸鱼TV/bin/php 或随应用内置"
            )
        }
        // 逐个候选尝试启动 (外部覆盖文件损坏时自动回退到内置)
        var lastErr: String? = null
        for (binary in binaries) {
            val server = ensureServer(context, binary, docRoot)
            if (server == null) {
                lastErr = binary.absolutePath
                continue
            }
            return try {
                Log.d(TAG, "proxy >> port ${server.first} $query")
                proxy(server.first, scriptFile.name, query)
            } catch (e: Exception) {
                Log.w(TAG, "proxy failed, restarting: ${e.message}")
                stop()
                val retry = ensureServer(context, binary, docRoot)
                    ?: return PhpScriptManager.PhpResult.Error(502, "PHP 解释器重启失败: ${e.message}")
                try {
                    Log.d(TAG, "proxy >> (retry) port ${retry.first} $query")
                    proxy(retry.first, scriptFile.name, query)
                } catch (e2: Exception) {
                    PhpScriptManager.PhpResult.Error(502, "PHP 脚本请求失败: ${e2.message}")
                }
            }
        }
        return PhpScriptManager.PhpResult.Error(502, "PHP 解释器启动失败: $lastErr (见日志 $TAG)")
    }

    private fun proxy(port: Int, scriptName: String, query: String): PhpScriptManager.PhpResult {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val url = buildString {
            append("http://127.0.0.1:").append(port).append('/')
            append(java.net.URLEncoder.encode(scriptName, "UTF-8"))
            if (query.isNotEmpty()) append('?').append(query)
        }
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            // 列表模式的自引用地址: 脚本从 Host(内网 php -S 随机端口)+SCRIPT_NAME 拼 baseUrl,
            // 该地址只在本回合有效且外部不可达; 改写为稳定的 /phpout 路由
            // (路径可能带 %XX 编码, servePhp 会解码; 不带 .m3u8 后缀也受理)。
            val selfFix = "http://127.0.0.1:$port/"
            val phpoutFix = "http://127.0.0.1:8080/phpout/"
            val location = resp.header("Location")?.replace(selfFix, phpoutFix)
            if (resp.code in 300..399 && !location.isNullOrBlank()) {
                return PhpScriptManager.PhpResult.Redirect(location)
            }
            val body = resp.body?.string()?.replace(selfFix, phpoutFix) ?: ""
            if (resp.isSuccessful) {
                val trimmed = body.trim()
                if (trimmed.isEmpty()) {
                    return PhpScriptManager.PhpResult.Error(502, "PHP 脚本无输出")
                }
                if (trimmed.contains("#EXTM3U")) {
                    return PhpScriptManager.PhpResult.M3u8(trimmed.substring(trimmed.indexOf("#EXTM3U")))
                }
                val first = trimmed.lineSequence().firstOrNull()?.trim() ?: ""
                if (first.startsWith("http://") || first.startsWith("https://")) {
                    return PhpScriptManager.PhpResult.Redirect(first)
                }
                return PhpScriptManager.PhpResult.M3u8(trimmed)
            }
            return PhpScriptManager.PhpResult.Error(
                if (resp.code == 404) 404 else 502,
                "PHP 错误(${resp.code}): ${body.take(300)}"
            )
        }
    }

    /** 候选二进制: 外部覆盖优先, 其次 curl 改名补丁副本, 再 APK 内置 */
    private fun findBinaryCandidates(context: Context): List<File> {
        val out = ArrayList<File>()
        // 1. 用户覆盖: /sdcard/摸鱼TV/bin/php (可放自带 TLS 的完整二进制)
        try {
            addIfPresent(out, File(File(Environment.getExternalStorageDirectory(), "摸鱼TV/bin"), "php"))
        } catch (_: Exception) {
        }
        // 2. APK 内置 libphp.so → 优先用 curl 函数改名补丁副本
        //    (补丁腾出 curl_* 函数名, 由 https_wrapper.php 的用户态垫片接管, 补齐 https 能力)
        try {
            val lib = File(context.applicationInfo.nativeLibraryDir, "libphp.so")
            if (lib.isFile) {
                val patched = ensurePatchedPhp(context, lib)
                if (patched != null) addIfPresent(out, patched)
            }
            // 提取出来的原生库若无执行位且非本应用所有(chmod 会失败),
            // 复制一份到 files/bin/php 自行加执行位 (Android 9 及以下可用)
            if (lib.isFile && !lib.canExecute()) {
                try {
                    val copy = File(File(context.filesDir, "bin"), "php.bundled")
                    if (!copy.isFile || copy.length() != lib.length()) {
                        copy.parentFile?.mkdirs()
                        lib.inputStream().use { ins ->
                            copy.outputStream().use { o -> ins.copyTo(o) }
                        }
                        copy.setExecutable(true, false)
                    }
                    addIfPresent(out, copy)
                } catch (e: Exception) {
                    Log.w(TAG, "copy bundled php failed: $e")
                }
            }
            addIfPresent(out, lib)
        } catch (_: Exception) {
        }
        // 3. files/bin/php (补丁副本或旧部署残留)
        addIfPresent(out, File(File(context.filesDir, "bin"), "php"))
        return out
    }

    private fun addIfPresent(out: ArrayList<File>, f: File) {
        if (!f.isFile) return
        try {
            if (!f.canExecute()) f.setExecutable(true, false)
        } catch (_: Exception) {
        }
        if (!out.any { it.absolutePath == f.absolutePath }) out.add(f)
    }

    /** curl 垫片要腾位的原生函数名单(改名后由 assets/php/https_wrapper.php 的用户态实现接管) */
    private val CURL_FN_NAMES = arrayOf(
        "curl_init", "curl_copy_handle", "curl_setopt", "curl_setopt_array", "curl_exec",
        "curl_getinfo", "curl_error", "curl_errno", "curl_close", "curl_reset", "curl_pause",
        "curl_multi_init", "curl_multi_add_handle", "curl_multi_remove_handle", "curl_multi_select",
        "curl_multi_exec", "curl_multi_getcontent", "curl_multi_info_read", "curl_multi_close",
        "curl_multi_errno", "curl_multi_setopt"
    )

    /**
     * ssl:// 套接字传输垫片要腾位的原生函数 (无 openssl 扩展时 "ssl" 传输层不存在,
     * stream_socket_client/fsockopen 连 ssl:// 必失败)。改名后 https_wrapper.php 的
     * 用户态实现接管 ssl/tls 方案, 转 127.0.0.1:8081 回环隧道由 Java 侧做真实 TLS。
     * 属可选项: 单个改名失败只影响对应函数的 ssl 能力, 不作废整个补丁。
     */
    private val XPORT_FN_NAMES = arrayOf("stream_socket_client", "fsockopen")
    private const val PHP_PATCH_TAG = "curlshim2"

    /**
     * 内置 libphp.so 无任何 TLS 能力(未编 openssl 扩展、libcurl 无 https 协议),
     * curl_* 与 file_get_contents('https') 原生必失败。
     *
     * 这里把二进制里 curl_* 原生函数的注册名原地等长改名 (curl_ → xurl_), 函数表
     * 释放同名位置后, https_wrapper.php (auto_prepend 注入) 里的用户态 curl_* 垫片
     * 才能声明, 脚本的 https 请求经 /phpfetch 回环由应用层 OkHttp 完成真实 TLS。
     * 用户自带带 TLS 的 php (摸鱼TV/bin/php) 时原生函数仍在, 垫片自动跳过, 无需此补丁。
     */
    private fun ensurePatchedPhp(context: Context, src: File): File? = synchronized(lock) {
        // 全局锁: 并发 execute 同时看到旧 marker 会重复写同一二进制,
        // 截断写会破坏正在运行的 php -S 子进程 (text 段被改) 导致其随机崩溃
        return try {
            val binDir = File(context.filesDir, "bin")
            val dst = File(binDir, "php")
            val marker = File(binDir, "php.patched")
            val want = "$PHP_PATCH_TAG:${src.length()}:${src.lastModified()}"
            if (!dst.isFile || dst.length() != src.length() || !marker.isFile || marker.readText() != want) {
                binDir.mkdirs()
                val bytes = src.readBytes()
                var total = 0
                for (name in CURL_FN_NAMES) {
                    val n = renameCString(bytes, name)
                    if (n == 0) {
                        Log.w(TAG, "php patch skipped: curl name not found: $name")
                        return null
                    }
                    total += n
                }
                for (name in XPORT_FN_NAMES) {
                    // 撞名保护: 目标名已存在则放弃该函数的改名 (只退化 ssl 能力, 不作废补丁)
                    if (countCString(bytes, "x" + name.substring(1)) > 0) {
                        Log.w(TAG, "php patch: skip $name rename (target name exists)")
                        continue
                    }
                    val n = renameCString(bytes, name)
                    if (n == 0) {
                        Log.w(TAG, "php patch: skip $name rename (not found)")
                    } else {
                        total += n
                    }
                }
                dst.writeBytes(bytes)
                marker.writeText(want)
                dst.setExecutable(true, false)
                Log.i(TAG, "php patched: renamed $total fn registrations -> ${dst.absolutePath}")
            }
            if (dst.isFile) dst else null
        } catch (t: Throwable) {
            Log.w(TAG, "patch php failed: $t")
            null
        }
    }

    /** 把所有独立 "<name>\0" 形式的 C 字符串首字节原地改为 'x' (等长改名), 返回替换次数 */
    private fun renameCString(bytes: ByteArray, name: String): Int {
        val nb = name.toByteArray(Charsets.US_ASCII)
        if (nb.isEmpty()) return 0
        var count = 0
        var i = 0
        val limit = bytes.size - nb.size
        while (i <= limit) {
            var j = 0
            while (j < nb.size && bytes[i + j] == nb[j]) { j++ }
            if (j == nb.size && i + j < bytes.size && bytes[i + j].toInt() == 0 &&
                (i == 0 || !isIdentByte(bytes[i - 1]))
            ) {
                bytes[i] = 'x'.toByte()
                count++
                i += nb.size
            } else {
                i++
            }
        }
        return count
    }

    /** 统计独立 C 字符串 "<name>\0" 的出现次数 (改名前的目标名撞名检查) */
    private fun countCString(bytes: ByteArray, name: String): Int {
        val nb = name.toByteArray(Charsets.US_ASCII)
        if (nb.isEmpty()) return 0
        var count = 0
        var i = 0
        val limit = bytes.size - nb.size
        while (i <= limit) {
            var j = 0
            while (j < nb.size && bytes[i + j] == nb[j]) { j++ }
            if (j == nb.size && i + j < bytes.size && bytes[i + j].toInt() == 0 &&
                (i == 0 || !isIdentByte(bytes[i - 1]))
            ) {
                count++
                i += nb.size
            } else {
                i++
            }
        }
        return count
    }

    /** 标识符字符: 前一字符是它时说明匹配落在更长名字内部 (如 pfsockopen 里的 fsockopen) */
    private fun isIdentByte(b: Byte): Boolean {
        val c = b.toInt().toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    }

    /**
     * CA 包: 复用 Chaquopy 打包的 certifi bundle (assets/chaquopy/cacert.pem),
     * 拷到 files/php/ 后通过 -d curl.cainfo 注入 — 静态编译的 curl 默认 CA 路径
     * 在 Android 上不存在, 不注入会导致 https 证书校验失败。
     */
    private fun ensureCaBundle(context: Context): String? = try {
        val f = File(File(context.filesDir, "php"), "cacert.pem")
        if (!f.isFile || f.length() == 0L) {
            f.parentFile?.mkdirs()
            context.assets.open("chaquopy/cacert.pem").use { ins ->
                f.outputStream().use { out -> ins.copyTo(out) }
            }
        }
        if (f.isFile && f.length() > 0L) f.absolutePath else null
    } catch (t: Throwable) {
        Log.w(TAG, "extract cacert failed: $t")
        null
    }

    /**
     * https 补齐层: 内置 php 无 openssl 扩展、libcurl 无 https 协议 —
     * assets/php/https_wrapper.php 通过 auto_prepend_file 注入:
     *   https 流包装器 + curl_* 用户态垫片, 请求统一转 /phpfetch 回环由 OkHttp 完成 TLS。
     */
    private fun ensureHttpsWrapper(context: Context): String? = try {
        val f = File(File(context.filesDir, "php"), "https_wrapper.php")
        // 始终用资产里的最新版覆盖 (资产变更后不能依赖存在性判断, 否则旧版残留会一直生效)
        f.parentFile?.mkdirs()
        context.assets.open("php/https_wrapper.php").use { ins ->
            f.outputStream().use { out -> ins.copyTo(out) }
        }
        if (f.isFile && f.length() > 0L) f.absolutePath else null
    } catch (t: Throwable) {
        Log.w(TAG, "extract https wrapper failed: $t")
        null
    }

    /** 确保常驻 php -S 子进程存活, 返回 (port, docRoot) */
    private fun ensureServer(context: Context, binary: File, docRoot: File): Pair<Int, File>? = synchronized(lock) {
        val existing = process
        if (existing != null && port > 0 && scriptDirPath == docRoot.absolutePath && isAlive(existing)) {
            if (ping(port)) return@synchronized Pair(port, docRoot)
        }
        stopLocked()

        val freePort = ServerSocket(0).use { it.localPort }
        val cmd = ArrayList<String>()
        cmd.add(binary.absolutePath)
        val ca = ensureCaBundle(context)
        if (ca != null) {
            cmd.add("-d"); cmd.add("curl.cainfo=$ca")
        }
        val httpsWrapper = ensureHttpsWrapper(context)
        if (httpsWrapper != null) {
            cmd.add("-d"); cmd.add("auto_prepend_file=$httpsWrapper")
        }
        cmd.add("-S"); cmd.add("127.0.0.1:$freePort")
        cmd.add("-t"); cmd.add(docRoot.absolutePath)

        val pb = ProcessBuilder(cmd)
            .directory(docRoot)
            .redirectErrorStream(true)
        try {
            val tmp = File(context.cacheDir, "php-tmp").apply { mkdirs() }
            pb.environment()["TMPDIR"] = tmp.absolutePath
            pb.environment()["HOME"] = context.filesDir.absolutePath
        } catch (_: Exception) {
        }
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "start php failed: ${cmd.joinToString(" ")}", e)
            return@synchronized null
        }
        // 吞掉输出避免子进程阻塞在管道上
        Thread {
            try {
                proc.inputStream.bufferedReader().use { it.forEachLine { line -> Log.d(TAG, "php: $line") } }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(proc)) {
                Log.e(TAG, "php exited early")
                return@synchronized null
            }
            if (ping(freePort)) {
                process = proc
                port = freePort
                scriptDirPath = docRoot.absolutePath
                Log.i(TAG, "php server started on $freePort, docroot=${docRoot.absolutePath}")
                return@synchronized Pair(freePort, docRoot)
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
        }
        Log.e(TAG, "php server start timeout")
        kill(proc)
        null
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /** destroyForcibly 是 API 26+, 低版本退回 destroy() */
    private fun kill(p: Process) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) p.destroyForcibly() else p.destroy()
        } catch (_: Exception) {
        }
    }

    private fun ping(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (_: Exception) {
        false
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        process?.let { kill(it) }
        process = null
        port = 0
        scriptDirPath = null
    }
}
