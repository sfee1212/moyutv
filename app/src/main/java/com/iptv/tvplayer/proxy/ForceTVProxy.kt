package com.iptv.tvplayer.proxy

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import com.forcetech.Util
import java.net.HttpURLConnection
import java.net.URL
import java.util.HashSet

import java.util.concurrent.ConcurrentHashMap

class ForceTVProxy(private val context: Context) : ServiceConnection {
    private val set = ConcurrentHashMap.newKeySet<String>()

    fun fetch(url: String): String {
        val scheme = Util.scheme(url)
        if (!set.contains(scheme)) {
            val intent = Util.intent(context, scheme)
            val bindResult = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            android.util.Log.d("ForceTVProxy", "bindService for $scheme returned $bindResult")
        }
        
        // Wait up to 30 seconds for service to bind (emulators/slow devices)
        var waitCount = 0
        while (!set.contains(scheme) && waitCount < 300) {
            SystemClock.sleep(100)
            waitCount++
        }
        
        if (!set.contains(scheme)) {
            // Service failed to bind in time
            android.util.Log.e("ForceTVProxy", "Service failed to bind within 20 seconds for $scheme")
            return url
        }
        
        val uri = Uri.parse(url)
        val port = Util.port(scheme)
        val id = uri.lastPathSegment
        val cmd = "http://127.0.0.1:$port/cmd.xml?cmd=switch_chan&server=${uri.host}:${uri.port}&id=$id"
        
        android.util.Log.d("ForceTVProxy", "Sending cmd: $cmd")
        
        // Retry making the request a few times in case the native server takes time to start listening
        var httpSuccess = false
        for (i in 0 until 5) {
            try {
                android.util.Log.d("ForceTVProxy", "Connecting to local server via raw socket (attempt ${i+1})")
                val socket = java.net.Socket("127.0.0.1", port)
                socket.soTimeout = 3000
                val request = "GET /cmd.xml?cmd=switch_chan&server=${uri.host}:${uri.port}&id=$id HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUser-Agent: MTV\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                
                // Just read a few bytes to ensure the server responded
                val response = ByteArray(1024)
                val read = socket.getInputStream().read(response)
                socket.close()
                
                if (read > 0) {
                    android.util.Log.d("ForceTVProxy", "cmd response: ${String(response, 0, read)}")
                    httpSuccess = true
                    break
                }
            } catch (e: Exception) {
                android.util.Log.e("ForceTVProxy", "Failed to connect to local server (attempt ${i+1})", e)
            }
            if (!httpSuccess) SystemClock.sleep(500)
        }
        
        return "http://127.0.0.1:$port/$id"
    }

    private fun trans(name: ComponentName): String {
        val cls = name.className.lowercase()
        return when {
            cls.contains("p2p") -> "p2p"
            cls.contains("p3p") -> "p3p"
            cls.contains("p9p") -> "p9p"
            cls.contains("forcetv") -> "forcetv"
            cls.contains("mitv") -> "mitv"
            else -> ""
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val t = trans(name)
        android.util.Log.d("ForceTVProxy", "onServiceConnected: ${name.className}, trans=$t")
        if (t.isNotEmpty()) set.add(t)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        val t = trans(name)
        android.util.Log.d("ForceTVProxy", "onServiceDisconnected: ${name.className}, trans=$t")
        if (t.isNotEmpty()) set.remove(t)
    }
}
