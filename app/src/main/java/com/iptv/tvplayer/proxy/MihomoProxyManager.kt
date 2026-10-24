package com.iptv.tvplayer.proxy

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader

object MihomoProxyManager {
    private const val TAG = "MihomoProxy"
    var proxyAddress = "127.0.0.1:10808" // Mihomo mixed-port
        private set

    private var process: Process? = null

    fun restart(context: Context) {
        Thread {
            process?.destroy()
            process = null
            Thread.sleep(1000)
            start(context)
        }.start()
    }

    fun start(context: Context) {
        if (process != null) {
            Log.d(TAG, "Mihomo proxy is already running.")
            return
        }

        Thread {
            try {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val mihomoFile = File(nativeLibDir, "libmihomo.so")

                if (!mihomoFile.exists() || !mihomoFile.canExecute()) {
                    Log.e(TAG, "Mihomo binary not found or not executable at: ${mihomoFile.absolutePath}")
                    return@Thread
                }

                // Prepare working directory
                val workDir = File(context.filesDir, "mihomo").apply { mkdirs() }
                
                // Prepare config.yaml
                val configFile = File(workDir, "config.yaml")
                writeConfig(configFile)

                val cmd = arrayOf(
                    mihomoFile.absolutePath,
                    "-d", workDir.absolutePath,
                    "-f", configFile.absolutePath
                )

                while (true) {
                    if (Thread.currentThread().isInterrupted) break

                    Log.d(TAG, "Starting Mihomo with command: ${cmd.joinToString(" ")}")

                    val pb = ProcessBuilder(*cmd)
                    pb.directory(workDir)
                    pb.redirectErrorStream(true)

                    process = pb.start()

                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, line ?: "")
                    }

                    val exitCode = process?.waitFor()
                    Log.w(TAG, "Mihomo process exited with code $exitCode. Restarting in 3 seconds...")

                    Thread.sleep(3000)
                }

            } catch (e: InterruptedException) {
                Log.d(TAG, "Mihomo proxy thread interrupted.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Mihomo proxy", e)
            } finally {
                process?.destroy()
                process = null
            }
        }.start()
    }

    private fun writeConfig(configFile: File) {
        val rawUrl = com.iptv.tvplayer.data.SettingsManager.youtubeProxyUrl.trim()
        val hasProxy = rawUrl.isNotBlank()
        
        val proxyProviderSection = if (hasProxy) {
            val providerUrl = if (rawUrl.startsWith("http", ignoreCase = true)) {
                rawUrl
            } else {
                "http://127.0.0.1:8080/proxy_node"
            }
            
            """
proxy-providers:
  free-nodes:
    type: http
    url: "$providerUrl"
    interval: 3600
    path: ./proxies/free-nodes.yaml
    health-check:
      enable: true
      interval: 300
      url: http://www.gstatic.com/generate_204

proxy-groups:
  - name: PROXY
    type: select
    use:
      - free-nodes
            """.trim()
        } else {
            """
proxy-groups:
  - name: PROXY
    type: select
    proxies:
      - DIRECT
            """.trim()
        }

        val configContent = """
mixed-port: 10808
allow-lan: true
mode: rule
log-level: info
ipv6: false
external-controller: 0.0.0.0:9090

$proxyProviderSection

rules:
  - DOMAIN-SUFFIX,filegear-sg.me,PROXY
  - DOMAIN-SUFFIX,filegear.me,PROXY
  - DOMAIN-SUFFIX,gitcdn.top,DIRECT
  - DOMAIN-SUFFIX,raw.githubusercontent.com,PROXY
  - DOMAIN-SUFFIX,youtube.com,PROXY
  - DOMAIN-SUFFIX,googlevideo.com,PROXY
  - DOMAIN-SUFFIX,ytimg.com,PROXY
  - DOMAIN-SUFFIX,ggpht.com,PROXY
  - MATCH,PROXY
        """.trim()

        FileWriter(configFile).use { writer ->
            writer.write(configContent)
        }
    }
}
