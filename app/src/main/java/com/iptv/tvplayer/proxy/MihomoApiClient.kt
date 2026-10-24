package com.iptv.tvplayer.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MihomoProxyNode(val name: String, val type: String, val delay: Int)

object MihomoApiClient {
    private const val BASE_URL = "http://127.0.0.1:9090"

    suspend fun getProxies(): List<MihomoProxyNode> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MihomoProxyNode>()
        try {
            val url = URL("$BASE_URL/proxies")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val proxies = json.getJSONObject("proxies")
                
                // Mihomo usually has a "PROXY" group
                val proxyGroup = proxies.optJSONObject("PROXY")
                if (proxyGroup != null) {
                    val allNames = proxyGroup.optJSONArray("all")
                    if (allNames != null) {
                        for (i in 0 until allNames.length()) {
                            val nodeName = allNames.getString(i)
                            val nodeObj = proxies.optJSONObject(nodeName)
                            if (nodeObj != null && nodeName != "DIRECT" && nodeName != "REJECT") {
                                val type = nodeObj.optString("type", "unknown")
                                val history = nodeObj.optJSONArray("history")
                                var delay = -1
                                if (history != null && history.length() > 0) {
                                    val lastEntry = history.getJSONObject(history.length() - 1)
                                    delay = lastEntry.optInt("delay", -1)
                                }
                                list.add(MihomoProxyNode(nodeName, type, delay))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext list
    }

    suspend fun testDelay(nodeName: String): Int = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/proxies/${java.net.URLEncoder.encode(nodeName, "UTF-8")}/delay?timeout=5000&url=http://www.gstatic.com/generate_204")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                return@withContext json.optInt("delay", -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext -1
    }

    suspend fun selectProxy(nodeName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/proxies/PROXY")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            val payload = "{\"name\": \"$nodeName\"}"
            connection.outputStream.use { os ->
                os.write(payload.toByteArray())
            }

            return@withContext connection.responseCode == 204
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }
}
