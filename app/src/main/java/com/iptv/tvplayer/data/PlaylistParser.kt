package com.iptv.tvplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object AESCrypt {
    // Obfuscated keys using XOR mask 0x5A
    private val oK = byteArrayOf(14, 44, 10, 54, 59, 35, 63, 40, 104, 106, 104, 108, 26, 17, 63, 35)
    private val oI = byteArrayOf(14, 44, 10, 54, 59, 35, 63, 40, 104, 106, 104, 108, 26, 19, 12, 123)
    private const val M = 0x5A.toByte()

    private fun dK(): ByteArray = oK.map { (it.toInt() xor M.toInt()).toByte() }.toByteArray()
    private fun dI(): ByteArray = oI.map { (it.toInt() xor M.toInt()).toByte() }.toByteArray()

    fun decrypt(encryptedBase64: String): String? {
        return try {
            val decodedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(dK(), "AES")
            val ivSpec = IvParameterSpec(dI())
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

object PlaylistParser {
    var lastError = ""

    private fun decodeBytes(bytes: ByteArray): String {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, java.nio.charset.Charset.forName("GBK"))
        }
    }

    suspend fun parse(urlStr: String): Playlist? = withContext(Dispatchers.IO) {
        lastError = ""
        if (urlStr.isBlank()) {
            lastError = "URL为空"
            return@withContext null
        }
        
        try {
            // Support url#headers#{"User-Agent":"Player"} format
            val parts = urlStr.split("#headers#")
            val targetUrl = parts[0]
            val headers = if (parts.size > 1) {
                // simple json parsing for headers map
                val headerStr = parts[1].trim().removePrefix("{").removeSuffix("}")
                headerStr.split(",").mapNotNull {
                    val kv = it.split(":")
                    if (kv.size == 2) {
                        kv[0].replace("\"", "").trim() to kv[1].replace("\"", "").trim()
                    } else null
                }.toMap()
            } else emptyMap()

            val lines = if (targetUrl.startsWith("file://") || targetUrl.startsWith("/")) {
                val filePath = targetUrl.replace("file://", "")
                val file = java.io.File(filePath)
                if (file.exists()) {
                    decodeBytes(file.readBytes()).lines()
                } else {
                    lastError = "本地文件不存在: $filePath"
                    return@withContext null
                }
            } else {
                val resolvedUrl = com.iptv.tvplayer.proxy.ProxyManager.resolveUrl(targetUrl)
                // Encode non-ASCII characters (e.g., Chinese) in URL
                val encodedUrl = resolvedUrl.replace(Regex("[^\\x00-\\x7F]+")) {
                    java.net.URLEncoder.encode(it.value, "UTF-8")
                }
                val url = java.net.URL(encodedUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                
                if (connection is javax.net.ssl.HttpsURLConnection) {
                    val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                    })
                    val sc = javax.net.ssl.SSLContext.getInstance("SSL")
                    sc.init(null, trustAll, java.security.SecureRandom())
                    connection.sslSocketFactory = sc.socketFactory
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                
                val timeoutMs = com.iptv.tvplayer.data.SettingsManager.timeoutSeconds * 1000
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", com.iptv.tvplayer.data.SettingsManager.userAgent)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                
                headers.forEach { (k, v) ->
                    connection.setRequestProperty(k, v)
                }

                if (connection.responseCode in 200..299) {
                    val inputStream = connection.inputStream
                    val bytes = inputStream.readBytes()
                    decodeBytes(bytes).lines()
                } else {
                    lastError = "HTTP错误: ${connection.responseCode} ${connection.responseMessage}"
                    return@withContext null
                }
            }
            var finalLines = lines
            if (finalLines.isNotEmpty()) {
                val fullText = finalLines.joinToString("\n").trim()
                // Simple heuristic to detect plain text playlist
                val isPlainText = fullText.startsWith("#EXTM3U") || fullText.contains(",#genre#")
                
                if (!isPlainText) {
                    val decryptedText = AESCrypt.decrypt(fullText)
                    if (!decryptedText.isNullOrEmpty() && (decryptedText.startsWith("#EXTM3U") || decryptedText.contains(",#genre#"))) {
                        finalLines = decryptedText.lines()
                    }
                }
            }
            
            return@withContext parseLines(finalLines)
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        }
        return@withContext null
    }

    private fun parseLines(lines: List<String>): Playlist {
        // Detect if M3U or TXT
        if (lines.any { it.startsWith("#EXTM3U") }) {
            return parseM3u(lines)
        }
        return parseTxt(lines)
    }

    private fun parseTxt(lines: List<String>): Playlist {
        val categories = mutableListOf<String>()
        val channelsMap = mutableMapOf<String, MutableList<Channel>>()
        
        var currentCategory = "未分类"
        var pendingName: String? = null
        val kodiProps = mutableMapOf<String, String>()
        
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue
            
            if (text.contains(",#genre#")) {
                currentCategory = text.split(",")[0].trim()
                if (!categories.contains(currentCategory)) {
                    categories.add(currentCategory)
                    channelsMap[currentCategory] = mutableListOf()
                }
                pendingName = null
                kodiProps.clear()
                continue
            }
            
            if (pendingName != null) {
                if (text.startsWith("#KODIPROP:")) {
                    val parts = text.removePrefix("#KODIPROP:").split("=", limit = 2)
                    if (parts.size == 2) {
                        kodiProps[parts[0].trim()] = parts[1].trim()
                    }
                } else if (!text.startsWith("#")) {
                    val drmType = kodiProps["inputstream.adaptive.license_type"]
                    val drmKey = kodiProps["inputstream.adaptive.license_key"]
                    
                    val channelList = channelsMap[currentCategory]!!
                    val existingChannel = channelList.find { it.name == pendingName }
                    if (existingChannel != null) {
                        val mutableUrls = existingChannel.urls.toMutableList()
                        mutableUrls.add(text)
                        val index = channelList.indexOf(existingChannel)
                        channelList[index] = existingChannel.copy(
                            urls = mutableUrls,
                            drmType = existingChannel.drmType ?: drmType,
                            drmKey = existingChannel.drmKey ?: drmKey
                        )
                    } else {
                        channelList.add(Channel(pendingName, listOf(text), null, drmType, drmKey))
                    }
                    
                    pendingName = null
                    kodiProps.clear()
                }
                continue
            }
            
            if (text.startsWith("#")) continue
            
            if (text.contains(",")) {
                val parts = text.split(",", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val urlOrProp = parts[1].trim()
                    
                    if (!categories.contains(currentCategory)) {
                        categories.add(currentCategory)
                        channelsMap[currentCategory] = mutableListOf()
                    }
                    
                    if (urlOrProp.startsWith("#KODIPROP:")) {
                        pendingName = name
                        kodiProps.clear()
                        val propParts = urlOrProp.removePrefix("#KODIPROP:").split("=", limit = 2)
                        if (propParts.size == 2) {
                            kodiProps[propParts[0].trim()] = propParts[1].trim()
                        }
                    } else {
                        val channelList = channelsMap[currentCategory]!!
                        val existingChannel = channelList.find { it.name == name }
                        if (existingChannel != null) {
                            val mutableUrls = existingChannel.urls.toMutableList()
                            mutableUrls.add(urlOrProp)
                            val index = channelList.indexOf(existingChannel)
                            channelList[index] = existingChannel.copy(urls = mutableUrls)
                        } else {
                            channelList.add(Channel(name, listOf(urlOrProp)))
                        }
                    }
                }
            }
        }
        
        return Playlist(categories, channelsMap)
    }

    private fun parseM3u(lines: List<String>): Playlist {
        val categories = mutableListOf<String>()
        val channelsMap = mutableMapOf<String, MutableList<Channel>>()
        
        var currentCategory = "未分类"
        var currentName = ""
        var currentLogo: String? = null
        var currentCatchup: String? = null
        var currentCatchupSource: String? = null
        var currentCatchupDays = 0
        val kodiProps = mutableMapOf<String, String>()
        
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue
            
            if (text.startsWith("#EXTINF")) {
                kodiProps.clear()
                // Parse group-title if exists
                val groupTitleRegex = "group-title=\"([^\"]+)\"".toRegex()
                val match = groupTitleRegex.find(text)
                if (match != null) {
                    currentCategory = match.groupValues[1]
                }
                
                // Parse tvg-logo or logo if exists
                val logoRegex = """(?:tvg-logo|logo)="([^"]+)"""".toRegex()
                val logoMatch = logoRegex.find(text)
                currentLogo = logoMatch?.groupValues?.get(1)
                
                // Parse catchup properties
                val catchupRegex = """catchup(?:-type)?"="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                val catchupSourceRegex = """catchup-source="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                val catchupDaysRegex = """(?:catchup-days|timeshift)="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                
                currentCatchup = catchupRegex.find(text)?.groupValues?.get(1)
                currentCatchupSource = catchupSourceRegex.find(text)?.groupValues?.get(1)
                currentCatchupDays = catchupDaysRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                // Parse channel name (after last comma)
                val commaIndex = text.lastIndexOf(",")
                if (commaIndex != -1) {
                    currentName = text.substring(commaIndex + 1).trim()
                }
            } else if (text.startsWith("#KODIPROP:")) {
                val parts = text.removePrefix("#KODIPROP:").split("=", limit = 2)
                if (parts.size == 2) {
                    kodiProps[parts[0].trim()] = parts[1].trim()
                }
            } else if (!text.startsWith("#")) {
                // It's a URL
                if (currentName.isNotEmpty()) {
                    if (!categories.contains(currentCategory)) {
                        categories.add(currentCategory)
                        channelsMap[currentCategory] = mutableListOf()
                    }
                    
                    val drmType = kodiProps["inputstream.adaptive.license_type"]
                    val drmKey = kodiProps["inputstream.adaptive.license_key"]
                    
                    val channelList = channelsMap[currentCategory]!!
                    val existingChannel = channelList.find { it.name == currentName }
                    if (existingChannel != null) {
                        val mutableUrls = existingChannel.urls.toMutableList()
                        mutableUrls.add(text)
                        val index = channelList.indexOf(existingChannel)
                        val finalLogo = existingChannel.logo ?: currentLogo
                        val finalDrmType = existingChannel.drmType ?: drmType
                        val finalDrmKey = existingChannel.drmKey ?: drmKey
                        val finalCatchup = existingChannel.catchup ?: currentCatchup
                        val finalCatchupSource = existingChannel.catchupSource ?: currentCatchupSource
                        val finalCatchupDays = if (existingChannel.catchupDays > 0) existingChannel.catchupDays else currentCatchupDays
                        channelList[index] = existingChannel.copy(
                            urls = mutableUrls,
                            logo = finalLogo,
                            drmType = finalDrmType,
                            drmKey = finalDrmKey,
                            catchup = finalCatchup,
                            catchupSource = finalCatchupSource,
                            catchupDays = finalCatchupDays
                        )
                    } else {
                        channelList.add(Channel(
                            name = currentName, 
                            urls = listOf(text), 
                            logo = currentLogo, 
                            drmType = drmType, 
                            drmKey = drmKey,
                            catchup = currentCatchup,
                            catchupSource = currentCatchupSource,
                            catchupDays = currentCatchupDays
                        ))
                    }
                }
                currentLogo = null // reset for next channel
                currentCatchup = null
                currentCatchupSource = null
                currentCatchupDays = 0
                kodiProps.clear()
            }
        }
        
        return Playlist(categories, channelsMap)
    }
}
