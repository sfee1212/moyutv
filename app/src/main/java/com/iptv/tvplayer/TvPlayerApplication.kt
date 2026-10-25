package com.iptv.tvplayer

import android.app.Application
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import kotlinx.coroutines.launch

class TvPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // MediaCodecList Hook to hide Rockchip hardware decoders
        try {
            // Trigger static initialization of MediaCodecList
            android.media.MediaCodecList.getCodecCount()
            
            val listClass = Class.forName("android.media.MediaCodecList")
            val fieldsToFilter = arrayOf("sAllCodecInfos", "sRegularCodecInfos")
            
            for (fieldName in fieldsToFilter) {
                try {
                    val field = listClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val codecInfos = field.get(null) as? Array<android.media.MediaCodecInfo>
                    if (codecInfos != null) {
                        val filteredList = ArrayList<android.media.MediaCodecInfo>()
                        for (info in codecInfos) {
                            val name = info.name
                            // Filter out Rockchip hardware video decoders that cause EGL texture snow/static screen in WebViews
                            if (name.startsWith("OMX.rk.video_decoder", ignoreCase = true)) {
                                android.util.Log.d("MediaCodecHook", "Hiding Rockchip hardware decoder from WebView: $name")
                            } else {
                                filteredList.add(info)
                            }
                        }
                        
                        // Reconstruct array and write it back using reflection
                        val newArray = java.lang.reflect.Array.newInstance(
                            android.media.MediaCodecInfo::class.java,
                            filteredList.size
                        ) as Array<android.media.MediaCodecInfo>
                        
                        for (i in filteredList.indices) {
                            newArray[i] = filteredList[i]
                        }
                        field.set(null, newArray)
                        android.util.Log.d("MediaCodecHook", "Successfully hooked MediaCodecList.$fieldName. New size: ${newArray.size}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaCodecHook", "Failed to hook MediaCodecList.$fieldName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaCodecHook", "Global MediaCodecList hook failure: ${e.message}")
        }


        // Print signature hash for release reference and run security checks
        android.util.Log.i("SecurityCheck", "App Signature SHA-256: " + com.iptv.tvplayer.utils.SecurityCheck.getSignatureSHA256(this))
        if (com.iptv.tvplayer.utils.SecurityCheck.checkSecurity(this)) {
            // 兼容性优先: 部分电视盒子自带 Xposed/LSPosed 或调试环境,
            // 直接杀进程会导致应用"闪退"无法启动。这里只记录日志, 不终止。
            android.util.Log.w("SecurityCheck", "Security check flagged this environment; continuing for compatibility")
        }

        // 尽早初始化设置 (SharedPreferences 在 Activity 之前就绪, 避免 lateinit 崩溃)
        try {
            com.iptv.tvplayer.data.SettingsManager.init(this)
        } catch (t: Throwable) {
            android.util.Log.e("TvPlayerApp", "SettingsManager init failed: ${t.message}", t)
        }

        // 尽早以绝对路径加载 MPV 库，绕过 Android 14/HyperOS linker namespace 限制
        try {
            `is`.xyz.mpv.MPV.loadLibraries(this)
        } catch (e: Throwable) {
            android.util.Log.e("TvPlayerApp", "MPV early load failed: ${e.message}")
        }

        // Configure TBS to allow multiple processes if needed, and optimize settings
        val map = HashMap<String, Any>()
        map[TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER] = true
        map[TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE] = true
        map["sandbox_isLowEndDevice"] = true // Force low end device profile to disable GPU acceleration in X5 Core
        QbSdk.initTbsSettings(map)

        // Prevent X5 from downloading automatically on startup unless enabled
        val prefs = getSharedPreferences("iptv_settings", android.content.Context.MODE_PRIVATE)
        val enableTbsX5 = prefs.getBoolean("enable_tbs_x5", false)
        
        if (enableTbsX5) {

            initX5Environment(this)

        }
        else {Log.d("TBS_X5", "X5 Core is disabled by user. Using system WebView.")
        }
    }

    companion object {
        fun initX5Environment(context: android.content.Context) {
            QbSdk.initX5Environment(context.applicationContext, object : QbSdk.PreInitCallback {
                override fun onCoreInitFinished() {
                    Log.d("TBS_X5", "X5 Core Init Finished")
                }

                override fun onViewInitFinished(isX5Core: Boolean) {
                    Log.d("TBS_X5", "X5 View Init Finished. isX5Core: $isX5Core")
                    if (isX5Core) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                context.applicationContext,
                                "腾讯X5内核加载成功",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            })
        }
        
        fun downloadAndInstallX5(context: android.content.Context, onProgress: (Int) -> Unit, onFinish: (Boolean) -> Unit) {
            try {
                QbSdk.reset(context.applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            val urlStr = "https://gh-proxy.com/https://github.com/sfee1212/SiTV-SERVER/releases/download/99/tbs_core.apk"
            val saveFile = java.io.File(context.cacheDir, "tbs_core.apk")
            
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    })
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAll, java.security.SecureRandom())
                    
                    var url = java.net.URL(urlStr)
                    var conn: java.net.HttpURLConnection? = null
                    var redirectCount = 0
                    
                    while (redirectCount < 10) {
                        conn = url.openConnection() as java.net.HttpURLConnection
                        if (conn is javax.net.ssl.HttpsURLConnection) {
                            conn.sslSocketFactory = sslContext.socketFactory
                            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                        }
                        conn.connectTimeout = 15000
                        conn.readTimeout = 60000
                        conn.instanceFollowRedirects = false
                        conn.connect()
                        
                        val responseCode = conn.responseCode
                        if (responseCode in 300..399) {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (location != null) {
                                url = java.net.URL(url, location)
                                redirectCount++
                            } else {
                                break
                            }
                        } else {
                            break
                        }
                    }
                    
                    if (conn != null && conn.responseCode in 200..299) {
                        val fileLength = conn.contentLength
                        val input = conn.inputStream
                        val output = java.io.FileOutputStream(saveFile)
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                val displayProgress = progress * 99 / 100
                                if (displayProgress > lastProgress) {
                                    lastProgress = displayProgress
                                    onProgress(displayProgress)
                                }
                            }
                            output.write(data, 0, count)
                        }
                        output.flush()
                        output.close()
                        input.close()
                        conn.disconnect()
                        
                        onProgress(100)
                        
                        var coreVersion = 46011
                        try {
                            val pm = context.packageManager
                            val info = pm.getPackageArchiveInfo(saveFile.absolutePath, 0)
                            if (info != null) {
                                coreVersion = info.versionCode
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        QbSdk.setTbsListener(object : com.tencent.smtt.sdk.TbsListener {
                            override fun onDownloadFinish(errCode: Int) {}
                            override fun onInstallFinish(errCode: Int) {
                                android.util.Log.d("TBS_X5", "Install finish: $errCode")
                                onFinish(errCode == 200 || errCode == 232)
                            }
                            override fun onDownloadProgress(progress: Int) {}
                        })
                        
                        QbSdk.installLocalTbsCore(context.applicationContext, coreVersion, saveFile.absolutePath)
                        
                    } else {
                        conn?.disconnect()
                        onFinish(false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    onFinish(false)
                }
            }
        }
    }
}
