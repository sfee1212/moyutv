package com.iptv.tvplayer.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSettingsMenu(
    onDismiss: () -> Unit,
    onMenuSelected: (String) -> Unit
) {
    val mainMenus = listOf(
        "列表配置", "画面比例", "解码方式", "超时换源", "其他设置"
    )
    
    val subMenusMap = mapOf(
        "列表配置" to listOf("列表订阅", "EPG订阅"),
        "解码方式" to listOf("MPV", "EXO"),
        "画面比例" to listOf("原始", "填充"),
        "超时换源" to listOf("10s", "15s", "20s", "25s", "30s"),
        "其他设置" to listOf("扫码推送", "断流重连", "UserAgent", "Headers")
    )

    var selectedMainMenu by remember { mutableStateOf("列表配置") }
    var focusedMainMenu by remember { mutableStateOf("列表配置") }
    var isInSubMenu by remember { mutableStateOf(false) }

    val mainMenuFocusRequesters = remember { mainMenus.associateWith { FocusRequester() } }
    val subMenuFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    // Request initial focus
    LaunchedEffect(Unit) {
        try {
            mainMenuFocusRequesters[selectedMainMenu]?.requestFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .clickable { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .clickable(enabled = false) {} // block clicks
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            // Sub Menu (Left Column)
            val subMenus = subMenusMap[focusedMainMenu] ?: emptyList()
            if (subMenus.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    items(subMenus.size) { index ->
                        val item = subMenus[index]
                        val focusRequester = subMenuFocusRequesters.getOrPut(item) { FocusRequester() }
                        var isFocused by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isFocused) Color(0xFF333333) else Color.Transparent)
                                .focusRequester(focusRequester)
                                .onFocusChanged { 
                                    isFocused = it.isFocused
                                    if (it.isFocused) {
                                        isInSubMenu = true
                                    }
                                }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyUp) {
                                        if (event.key == Key.DirectionRight) {
                                            isInSubMenu = false
                                            try { mainMenuFocusRequesters[focusedMainMenu]?.requestFocus() } catch (e: Exception) {}
                                            return@onKeyEvent true
                                        } else if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                            onMenuSelected(item)
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                }
                                .clickable {
                                    onMenuSelected(item)
                                }
                                .padding(16.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = item,
                                color = if (isFocused) Color.White else Color.LightGray,
                                fontSize = 18.sp,
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Main Menu (Right Column)
            LazyColumn(
                modifier = Modifier.width(140.dp)
            ) {
                items(mainMenus.size) { index ->
                    val item = mainMenus[index]
                    val focusRequester = mainMenuFocusRequesters[item]!!
                    var isFocused by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isFocused || (!isInSubMenu && item == focusedMainMenu)) Color(0xFF333333) else Color.Transparent)
                            .focusRequester(focusRequester)
                            .onFocusChanged { 
                                isFocused = it.isFocused 
                                if (it.isFocused) {
                                    focusedMainMenu = item
                                    selectedMainMenu = item
                                    isInSubMenu = false
                                }
                            }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp) {
                                    if (event.key == Key.DirectionLeft) {
                                        val currentSubMenus = subMenusMap[item]
                                        if (!currentSubMenus.isNullOrEmpty()) {
                                            isInSubMenu = true
                                            try { subMenuFocusRequesters[currentSubMenus.first()]?.requestFocus() } catch (e: Exception) {}
                                            return@onKeyEvent true
                                        }
                                    } else if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                        val currentSubMenus = subMenusMap[item]
                                        if (!currentSubMenus.isNullOrEmpty()) {
                                            isInSubMenu = true
                                            try { subMenuFocusRequesters[currentSubMenus.first()]?.requestFocus() } catch (e: Exception) {}
                                            return@onKeyEvent true
                                        } else {
                                            onMenuSelected(item)
                                            return@onKeyEvent true
                                        }
                                    }
                                }
                                false
                            }
                            .clickable {
                                focusedMainMenu = item
                                selectedMainMenu = item
                                val currentSubMenus = subMenusMap[item]
                                if (!currentSubMenus.isNullOrEmpty()) {
                                    isInSubMenu = true
                                    try { subMenuFocusRequesters[currentSubMenus.first()]?.requestFocus() } catch (e: Exception) {}
                                } else {
                                    onMenuSelected(item)
                                }
                            }
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item,
                            color = if (isFocused || item == focusedMainMenu) Color(0xFF007BFF) else Color.Gray,
                            fontSize = 18.sp,
                            fontWeight = if (isFocused || item == focusedMainMenu) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
