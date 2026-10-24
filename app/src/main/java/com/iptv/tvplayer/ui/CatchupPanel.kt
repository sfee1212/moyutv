package com.iptv.tvplayer.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iptv.tvplayer.data.Channel
import com.iptv.tvplayer.data.EpgManager
import com.iptv.tvplayer.data.EpgProgram
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CatchupPanel(
    channel: Channel,
    playingProgram: EpgProgram?, // To highlight currently playing catchup program
    onPlayCatchup: (EpgProgram, String) -> Unit,
    onDismiss: () -> Unit
) {
    var programsByDay by remember { mutableStateOf<LinkedHashMap<String, List<EpgProgram>>>(LinkedHashMap()) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    
    val dayFocusRequester = remember { FocusRequester() }
    
    LaunchedEffect(channel) {
        val grouped = EpgManager.getProgramsGroupedByDay(channel.name, channel.catchupDays.takeIf { it > 0 } ?: 7)
        programsByDay = grouped
        if (grouped.isNotEmpty()) {
            selectedDay = grouped.keys.first()
            delay(100)
            try { dayFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Catchup URL generation logic
    val generateCatchupUrl = { program: EpgProgram ->
        val baseUrl = channel.urls.firstOrNull() ?: ""
        var resultUrl = baseUrl
        
        val startFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val startStr = startFormat.format(Date(program.startMillis))
        val endStr = startFormat.format(Date(program.endMillis))
        
        val catchupSource = channel.catchupSource
        if (!catchupSource.isNullOrBlank()) {
            // Replace {Y}{m}{d}{H}{M}{S} or {b} / {e}
            var appended = catchupSource
                .replace("{b}", startStr)
                .replace("{e}", endStr)
                .replace("{Y}", startStr.substring(0, 4))
                .replace("{m}", startStr.substring(4, 6))
                .replace("{d}", startStr.substring(6, 8))
                .replace("{H}", startStr.substring(8, 10))
                .replace("{M}", startStr.substring(10, 12))
                .replace("{S}", startStr.substring(12, 14))
            
            val connector = if (appended.startsWith("?") || appended.startsWith("&")) "" 
                           else if (resultUrl.contains("?")) "&" else "?"
            resultUrl += connector + appended
        } else {
            // Default CMCC PLTV / socks5 parameter format if no catchup-source in M3U
            if (resultUrl.startsWith("sk:/") || resultUrl.contains("ottrrs.hl.chinamobile.com")) {
                val connector = if (resultUrl.contains("?")) "&" else "?"
                resultUrl += "${connector}starttime=${startStr}&endtime=${endStr}"
            } else {
                // Generic playseek for others
                val connector = if (resultUrl.contains("?")) "&" else "?"
                resultUrl += "${connector}playseek=${startStr}-${endStr}"
            }
        }
        resultUrl
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.35f) // Take up ~35% of the screen horizontally
            .background(Color(0xE61E1E1E)) // Dark background
    ) {
        // Left column: Days
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(100.dp)
                .background(Color(0xFF252525))
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                val days = programsByDay.keys.toList()
                items(days.size) { index ->
                    val day = days[index]
                    val isSelected = selectedDay == day
                    var isFocused by remember { mutableStateOf(false) }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .then(if (index == 0) Modifier.focusRequester(dayFocusRequester) else Modifier)
                            .focusable()
                            .onFocusChanged { 
                                isFocused = it.isFocused
                                if (it.isFocused) {
                                    selectedDay = day
                                }
                            }
                            .background(if (isFocused) Color(0x66FFFFFF) else if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                            .clickable { selectedDay = day }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = day.split(" ").first(), // Only show "今天", "昨天" etc.
                            color = if (isSelected) Color(0xFFFFD700) else Color.White,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Divider
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0x33FFFFFF)))

        // Right column: Programs
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            val programs = programsByDay[selectedDay] ?: emptyList()
            if (programs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无节目信息", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                val listState = rememberLazyListState()
                val now = System.currentTimeMillis()
                
                LazyColumn(state = listState) {
                    items(programs.size) { index ->
                        val program = programs[index]
                        val isPlaying = playingProgram == program
                        val isFuture = program.startMillis > now
                        var isFocused by remember { mutableStateOf(false) }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .focusable(!isFuture) // Future programs cannot be played
                                .onFocusChanged { isFocused = it.isFocused }
                                .background(if (isFocused) Color(0x66FFFFFF) else Color.Transparent)
                                .clickable { 
                                    if (!isFuture) {
                                        val url = generateCatchupUrl(program)
                                        onPlayCatchup(program, url)
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = program.startTimeStr,
                                color = if (isPlaying) Color(0xFFFFD700) else if (isFuture) Color.DarkGray else Color.LightGray,
                                fontSize = 14.sp,
                                modifier = Modifier.width(50.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = program.title,
                                color = if (isPlaying) Color(0xFFFFD700) else if (isFuture) Color.DarkGray else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("播放中", color = Color(0xFFFFD700), fontSize = 12.sp)
                            } else if (!isFuture) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("回看", color = Color(0xFF4CAF50), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
