package com.iptv.tvplayer.script

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 通用 Python 脚本执行器 (Chaquopy 内嵌 CPython)
 *
 * 频道行:  dfws,local://wxty.py?id=dfws
 * 脚本放:  摸鱼TV/py/<名字>.py  (兼容 摸鱼TV/PHP)
 *
 * - 脚本通过 os.environ["QUERY_STRING"] / sys.argv[1] 拿参数
 * - 脚本 print("http://...")      → 302
 * - 脚本 print(m3u8文本)          → m3u8
 *
 * 注意: Chaquopy 为进程内解释器, 脚本死循环无法强杀, 用看门狗超时放弃本次结果,
 *       并销毁重建解释器实例避免污染后续执行。
 */
object PyScriptManager {
    private const val TAG = "PyScriptManager"
    private const val EXEC_TIMEOUT_SEC = 25L

    @Volatile private var started = false
    @Volatile private var initBegun = false
    private var startError: String? = null
    private val initLatch = java.util.concurrent.CountDownLatch(1)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "py-script").apply { isDaemon = true }
    }

    /** 由调用方在派发初始化线程前同步标记 (冷启动即播时用它判断"初始化进行中"而非"未初始化") */
    fun markBegun() {
        initBegun = true
    }

    fun init(context: Context) {
        initBegun = true
        synchronized(this) {
            if (started) {
                initLatch.countDown()
                return
            }
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                // 注入数据目录 (CatVod 兼容层的 TTL 缓存 / local 存储落盘位置)
                try {
                    Python.getInstance().getModule("script_runner").callAttr("setup", context.filesDir.absolutePath)
                } catch (t: Throwable) {
                    Log.e(TAG, "script_runner.setup failed", t)
                }
                started = true
                Log.i(TAG, "Python runtime started")
            } catch (t: Throwable) {
                startError = t.message
                Log.e(TAG, "Python start failed", t)
            } finally {
                initLatch.countDown()
            }
        }
    }

    /**
     * 等待后台初始化完成 (冷启动恢复上次频道时播放请求常早于 Python.start 完成)。
     * 返回 null = 就绪; 否则为错误信息。
     */
    private fun awaitRuntime(): String? {
        if (started) return null
        if (!initBegun) return "Python 运行时未初始化"
        return try {
            initLatch.await(20, TimeUnit.SECONDS)
            if (started) null
            else "Python 运行时不可用: ${startError ?: "初始化失败"}"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "Python 运行时初始化被中断"
        }
    }

    fun isAvailable(): Boolean = started

    fun execute(scriptFile: File, query: String): PhpScriptManager.PhpResult {
        return runWatchdog(scriptFile.name) {
            awaitRuntime()?.let { return@runWatchdog PhpScriptManager.PhpResult.Error(502, it) }
            try {
                val runner = Python.getInstance().getModule("script_runner")
                val res = runner.callAttr("run", scriptFile.absolutePath, query)
                val ok = pyStr(res, "ok").toBoolean()
                val output = pyStr(res, "output")
                val error = pyStr(res, "error")
                if (!ok) {
                    Log.e(TAG, "script error: $scriptFile\n$error")
                    return@runWatchdog PhpScriptManager.PhpResult.Error(502, "Python脚本异常: ${error.takeLast(300)}")
                }
                val classified = classify(output)
                // 空输出但脚本其实定义了 CatVod 爬虫类 → 给出准确指引
                if (classified is PhpScriptManager.PhpResult.Error && classified.message == "脚本无输出") {
                    val hasSpider = try {
                        runner.callAttr("has_spider", scriptFile.absolutePath).toString().toBoolean()
                    } catch (t: Throwable) { false }
                    if (hasSpider) {
                        return@runWatchdog PhpScriptManager.PhpResult.Error(
                            502, "此脚本为 CatVod 爬虫类, 请在频道行用 local://${scriptFile.name}?api=playerContent&args=[...] 协议接口调用"
                        )
                    }
                }
                classified
            } catch (t: Throwable) {
                Log.e(TAG, "execute failed: $scriptFile", t)
                PhpScriptManager.PhpResult.Error(502, "Python脚本执行失败: ${t.message}")
            }
        }
    }

    /**
     * CatVod 爬虫协议调用 (FongMi/CatVodSpider 接口):
     * script_runner.call() 内完成类发现 / init(extend) / 方法调用, 结果为 JSON 文本。
     */
    fun callApi(scriptFile: File, ext: String, method: String, argsJson: String): PhpScriptManager.PhpResult {
        return runWatchdog(scriptFile.name) {
            awaitRuntime()?.let { return@runWatchdog PhpScriptManager.PhpResult.Error(502, it) }
            try {
                val runner = Python.getInstance().getModule("script_runner")
                val res = runner.callAttr("call", scriptFile.absolutePath, ext, method, argsJson)
                val ok = pyStr(res, "ok").toBoolean()
                val result = pyStr(res, "result")
                val error = pyStr(res, "error")
                if (!ok) {
                    Log.e(TAG, "spider error: $scriptFile#$method\n$error")
                    return@runWatchdog PhpScriptManager.PhpResult.Error(502, "CatVod脚本异常: ${error.takeLast(300)}")
                }
                PhpScriptManager.PhpResult.Json(result.ifEmpty { "null" })
            } catch (t: Throwable) {
                Log.e(TAG, "callApi failed: $scriptFile#$method", t)
                PhpScriptManager.PhpResult.Error(502, "CatVod脚本调用失败: ${t.message}")
            }
        }
    }

    /** Chaquopy PyObject.get(String) 对已有 key 返回 null, 必须走 Python 原生 dict.get */
    private fun pyStr(res: com.chaquo.python.PyObject, key: String): String {
        val v = res.callAttr("get", key) ?: return ""
        val s = v.toString()
        return if (s == "None") "" else s
    }

    private fun runWatchdog(tag: String, task: () -> PhpScriptManager.PhpResult): PhpScriptManager.PhpResult {
        val future = executor.submit(task)
        return try {
            future.get(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            Log.e(TAG, "script timeout: $tag")
            PhpScriptManager.PhpResult.Error(504, "Python脚本执行超时(${EXEC_TIMEOUT_SEC}s)")
        } catch (e: Exception) {
            PhpScriptManager.PhpResult.Error(502, "Python脚本执行失败: ${e.message}")
        }
    }

    /** stdout 输出分类: m3u8 / 单行URL(302) / 其他按 m3u8 尝试 */
    internal fun classify(output: String): PhpScriptManager.PhpResult {
        val text = output.trim()
        if (text.isEmpty()) {
            return PhpScriptManager.PhpResult.Error(502, "脚本无输出")
        }
        if (text.contains("#EXTM3U")) {
            val idx = text.indexOf("#EXTM3U")
            return PhpScriptManager.PhpResult.M3u8(text.substring(idx))
        }
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
            return PhpScriptManager.PhpResult.Redirect(firstLine)
        }
        // 无 #EXTM3U 但也可能是 DIYP 风格的多行 url 列表, 原样当 m3u8 返回
        return PhpScriptManager.PhpResult.M3u8(text)
    }
}
