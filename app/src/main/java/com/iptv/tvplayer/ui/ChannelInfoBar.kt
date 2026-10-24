package com.iptv.tvplayer.ui

import com.iptv.tvplayer.data.WeatherInfo
import com.iptv.tvplayer.data.codeToEmoji

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import android.net.TrafficStats
import java.io.RandomAccessFile

@Composable
fun ChannelInfoBar(
    channelNumber: String,
    channelName: String,
    resolution: String = "1080P",
    networkType: String = "IPV4",
    currentLineIndex: Int = 1,
    totalLines: Int = 1,
    decoderType: String = "MPV",
    curProgram: com.iptv.tvplayer.data.EpgProgram? = null,
    nextProgram: com.iptv.tvplayer.data.EpgProgram? = null,
    weatherInfo: WeatherInfo? = null
) {
    // Dynamic states
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }
    var dayOfWeekStr by remember { mutableStateOf("") }
    var networkSpeed by remember { mutableStateOf("0 KB/s") }
    var cpuTemp by remember { mutableIntStateOf(52) }
    var oscamUptimeSec by remember { mutableIntStateOf(2989) } // Starts at ~49 mins

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINESE)
        val dateFormat = SimpleDateFormat("MM月dd日", Locale.CHINESE)
        val dayFormat = SimpleDateFormat("EEEE", Locale.CHINESE)
        var lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        var ticks = 0
        
        while (true) {
            val now = Date()
            currentTimeStr = timeFormat.format(now)
            currentDateStr = dateFormat.format(now)
            dayOfWeekStr = dayFormat.format(now)
            
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val diffBytes = currentRxBytes - lastTotalRxBytes
            lastTotalRxBytes = currentRxBytes
            networkSpeed = if (diffBytes >= 0) {
                val speedKb = diffBytes / 1024
                if (speedKb > 1024) String.format(Locale.US, "%.1f MB/s", speedKb / 1024f) else "$speedKb KB/s"
            } else "0 KB/s"
            
            ticks++
            if (ticks % 3 == 0) {
                cpuTemp = getCpuTempMock()
            }
            
            oscamUptimeSec++
            delay(1000)
        }
    }

    // Parse EPG details
    val nowMillis = System.currentTimeMillis()
    val progress = if (curProgram != null && curProgram.endMillis > curProgram.startMillis) {
        ((nowMillis - curProgram.startMillis).toFloat() / (curProgram.endMillis - curProgram.startMillis).toFloat()).coerceIn(0f, 1f)
    } else 0.5f

    val elapsedMins = if (curProgram != null) {
        ((nowMillis - curProgram.startMillis) / 60000).coerceAtLeast(0)
    } else 30

    val remainingMins = if (curProgram != null) {
        ((curProgram.endMillis - nowMillis) / 60000).coerceAtLeast(0)
    } else 30

    val localIp = remember { getLocalIpAddress() }

    val psm = com.iptv.tvplayer.player.PlayerStateManager
    val resLabel  by remember { derivedStateOf { psm.resolutionLabel } }
    val audioLbl  by remember { derivedStateOf { psm.audioLabel } }
    val ratioLbl  by remember { derivedStateOf { psm.aspectRatioLabel } }
    val vCodecLbl by remember { derivedStateOf { psm.videoCodecLabel } }


    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(145.dp)
            .background(Color(0xE60A0D16)) // 90% opacity deep dark blue-grey
            .drawBehind {
                // Top border line
                drawLine(
                    color = Color(0x33FFFFFF),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================== LEFT & MIDDLE SECTION ==================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Channel Title & Badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Logo/Label placeholder
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF22283A), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (channelName.contains("HD", ignoreCase = true)) "HD" else "SD",
                            color = Color(0xFFFFD700), // Gold
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Number + Name（可选择是否显示频道号）
                    val showNum = com.iptv.tvplayer.data.SettingsManager.showChannelNumberState.value
                    val cleanChannelName = channelName.replace(Regex("^\\d+\\.\\s*"), "")
                    val displayText = if (showNum && channelNumber.isNotBlank()) "$channelNumber. $cleanChannelName" else cleanChannelName
                    Text(
                        text = displayText,
                        color = Color(0xFF00D2FF), // Vibrant Cyan
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Badges（动态：画质 / 音频格式 / 宽高比）
                    BadgeText(text = resLabel)
                    Spacer(modifier = Modifier.width(4.dp))
                    BadgeText(text = audioLbl)
                    Spacer(modifier = Modifier.width(4.dp))
                    BadgeText(text = ratioLbl)
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // IP Address
                    Text(
                        text = localIp,
                        color = Color(0xFF00FF7F), // Spring Green
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 2. Current EPG & Progress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = curProgram?.let { "${it.startTimeStr} ~ ${it.endTimeStr}  ${it.title}" } ?: "精彩节目",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // EPG Progress Bar
                    Box(
                        modifier = Modifier
                            .width(150.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF007BFF), Color(0xFF00D2FF))
                                    )
                                )
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // EPG remaining time
                    Text(
                        text = "+$elapsedMins 分钟",
                        color = Color(0xFF00D2FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$remainingMins 分钟",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                // 3. Next EPG
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = nextProgram?.let { "${it.startTimeStr} ~ ${it.endTimeStr}  ${it.title}" } ?: "稍后: 精彩节目",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4. Bottom Line Technical Info & Status Meters
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Resolution & Decoder Info
                    Text(
                        text = "$resolution | $decoderType | 线路$currentLineIndex/$totalLines | $networkType",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Signal Strength
                    SignalMeter(label = "强度", value = 92, activeColor = Color(0xFF00FF7F))
                    Spacer(modifier = Modifier.width(12.dp))
                    // Signal Quality
                    SignalMeter(label = "质量", value = 88, activeColor = Color(0xFF00FF7F))
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Stream type & CPU（视频编码动态获取）
                    Text(
                        text = "$vCodecLbl & $audioLbl | CPU: $cpuTemp°C",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // ================== RIGHT CURVED WEATHER & TIME PANEL ==================
            Box(
                modifier = Modifier
                    .width(310.dp)
                    .fillMaxHeight()
                    .clip(RightPanelShape)
                    .background(Color(0xFF0C101E)) // Darker solid blue-black for contrast
                    .drawBehind {
                        // Draw the beautiful blue curve border on the left edge
                        val curveOffset = 45.dp.toPx()
                        val path = Path().apply {
                            moveTo(curveOffset, 0f)
                            quadraticBezierTo(
                                0f, size.height / 2f,
                                curveOffset, size.height
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF00D2FF),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    .padding(start = 45.dp, top = 6.dp, bottom = 4.dp, end = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (weatherInfo == null) {
                            Text("Loading...", color = Color.Gray, fontSize = 10.sp)
                        } else {
                            // Today Weather
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(codeToEmoji(weatherInfo.today.weatherCode), fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        "今日 ${weatherInfo.currentTempC}°",
                                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${weatherInfo.today.maxTempC}°/${weatherInfo.today.minTempC}° ${weatherInfo.today.desc}",
                                        color = Color.Gray, fontSize = 9.sp
                                    )
                                }
                            }

                            // Tomorrow Weather
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(codeToEmoji(weatherInfo.tomorrow.weatherCode), fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        "明日 ${weatherInfo.tomorrow.maxTempC}°",
                                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${weatherInfo.tomorrow.maxTempC}°/${weatherInfo.tomorrow.minTempC}° ${weatherInfo.tomorrow.desc}",
                                        color = Color.Gray, fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }

                    // 2. Clock & Date
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Large Time
                        Text(
                            text = currentTimeStr,
                            color = Color(0xFF00D2FF), // Cyan clock
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alignByBaseline()
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        // Date Column
                        Column(modifier = Modifier.alignByBaseline()) {
                            Text(
                                text = "$currentDateStr $dayOfWeekStr",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 3. OSCam Uptime Status Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val min = oscamUptimeSec / 60
                        val sec = oscamUptimeSec % 60
                        Text(
                            text = "OSCam_11871-802 | Uptime ${min}m ${sec}s",
                            color = Color.DarkGray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeText(text: String) {
    Box(
        modifier = Modifier
            .border(1.dp, Color.Gray, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = Color.LightGray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SignalMeter(label: String, value: Int, activeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 4.dp)
        )
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(Color(0x33FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value / 100f)
                    .background(activeColor)
            )
        }
        Text(
            text = "$value%",
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

// Right panel concave shape definition
val RightPanelShape = object : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val curveOffset = with(density) { 45.dp.toPx() }
        val path = Path().apply {
            moveTo(curveOffset, 0f)
            quadraticBezierTo(
                0f, size.height / 2f,
                curveOffset, size.height
            )
            lineTo(size.width, size.height)
            lineTo(size.width, 0f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

// Helper to get local IP
private fun getLocalIpAddress(): String {
    try {
        val en = java.net.NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                    val ip = inetAddress.hostAddress
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                }
            }
        }
    } catch (ex: Exception) {}
    return "192.168.1.100"
}

// Helper to mock CPU temp
private fun getCpuTempMock(): Int {
    return try {
        val reader = RandomAccessFile("/sys/class/thermal/thermal_zone0/temp", "r")
        val line = reader.readLine()
        reader.close()
        val temp = line.toDouble() / 1000.0
        if (temp in 10.0..99.0) temp.toInt() else 52
    } catch (e: Exception) {
        // Fallback to random fluctuation around 52°C
        50 + Random().nextInt(6)
    }
}
