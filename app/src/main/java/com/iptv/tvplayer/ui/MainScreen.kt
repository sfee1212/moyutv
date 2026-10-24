package com.iptv.tvplayer.ui

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.iptv.tvplayer.data.WeatherManager
import com.iptv.tvplayer.data.WeatherInfo

@Composable
fun MainScreen(
    playerContent: @Composable (Modifier) -> Unit,
    onChannelSelected: (String) -> Unit
) {
    val subscriptionUrl = com.iptv.tvplayer.data.SettingsManager.subscriptionUrlState.value
    
    // Dynamic playlist state
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var channels by remember { mutableStateOf(emptyMap<String, List<com.iptv.tvplayer.data.Channel>>()) }
    
    // Favorites State
    var favorites by remember { mutableStateOf(com.iptv.tvplayer.data.SettingsManager.favorites) }
    
    var selectedCategory by remember { mutableStateOf("") }
    var playingChannel by remember { mutableStateOf<com.iptv.tvplayer.data.Channel?>(null) }
    var playingUrl by remember { mutableStateOf("") }
    var currentLineIndex by remember { mutableStateOf(0) }
    
    // Auto-hide UI state
    var isUiVisible by remember { mutableStateOf(true) }
    var isInfoVisible by remember { mutableStateOf(false) }
    var userActionTrigger by remember { mutableStateOf(0L) }
    
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var subscriptionDialogType by remember { mutableStateOf("") }

    val context = LocalContext.current
    LaunchedEffect(com.iptv.tvplayer.data.SettingsManager.activeEpgUrlState.value, channels) {
        if (channels.isNotEmpty()) {
            com.iptv.tvplayer.data.EpgManager.activeChannelNames.clear()
            channels.values.forEach { list ->
                list.forEach { com.iptv.tvplayer.data.EpgManager.activeChannelNames.add(it.name) }
            }
            com.iptv.tvplayer.data.EpgManager.loadEpg(context)
        }
    }
    
    var epgTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60000)
            epgTick++
        }
    }

    // 观察天气信息流，自动重组
    val weatherInfo by WeatherManager.weatherInfoFlow.collectAsState()
    
    LaunchedEffect(Unit) {
        while (true) {
            WeatherManager.fetchWeather()
            delay(30 * 60 * 1000L) // 30 minutes
        }
    }

    // Fetch playlist
    LaunchedEffect(subscriptionUrl, favorites) {
        if (subscriptionUrl.isNotEmpty()) {
            if (categories.isEmpty() || categories.first() == "正在加载订阅...") {
                categories = listOf("正在加载订阅...")
            }
            val playlist = com.iptv.tvplayer.data.PlaylistParser.parse(subscriptionUrl)
            if (playlist != null && playlist.categories.isNotEmpty()) {
                val favChannels = playlist.channelsMap.values.flatten().filter { ch -> ch.urls.any { favorites.contains(it) } }.distinctBy { it.name }
                
                val finalCategories = if (favChannels.isNotEmpty()) listOf("我的收藏") + playlist.categories else playlist.categories
                val finalChannels = if (favChannels.isNotEmpty()) mapOf("我的收藏" to favChannels) + playlist.channelsMap else playlist.channelsMap
                
                categories = finalCategories
                channels = finalChannels
                
                // Auto-resume last played
                val lastUrl = com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl
                var found = false
                if (lastUrl.isNotEmpty()) {
                    for ((cat, catChannels) in channels) {
                        for (ch in catChannels) {
                            val idx = ch.urls.indexOf(lastUrl)
                            if (idx >= 0) {
                                // Don't override selected category if user is navigating, 
                                // only set on first load
                                if (selectedCategory.isEmpty() || selectedCategory == "正在加载订阅...") {
                                    selectedCategory = cat
                                }
                                playingChannel = ch
                                currentLineIndex = idx
                                playingUrl = lastUrl
                                onChannelSelected(lastUrl)
                                isUiVisible = false
                                isInfoVisible = true
                                userActionTrigger = System.currentTimeMillis()
                                found = true
                                break
                            }
                        }
                        if (found) break
                    }
                }
                
                if (!found && (selectedCategory.isEmpty() || selectedCategory == "正在加载订阅...")) {
                    selectedCategory = categories.first()
                }
            } else {
                val errorMsg = com.iptv.tvplayer.data.PlaylistParser.lastError
                if (errorMsg.isNotEmpty()) {
                    categories = listOf("加载失败: $errorMsg")
                } else {
                    categories = listOf("加载失败或列表为空")
                }
                channels = emptyMap()
            }
        } else {
            categories = emptyList()
            channels = emptyMap()
        }
    }

    // Auto-hide timer
    LaunchedEffect(isUiVisible, isInfoVisible, userActionTrigger, showSettingsMenu, showSubscriptionDialog) {
        if ((isUiVisible || isInfoVisible) && !showSettingsMenu && !showSubscriptionDialog) {
            delay(8000) // 8 seconds of inactivity for DIYP
            isUiVisible = false
            isInfoVisible = false
        }
    }

    val keepAwake = { 
        isUiVisible = true
        isInfoVisible = false
        userActionTrigger = System.currentTimeMillis() 
    }

    val showInfoOnly = {
        isInfoVisible = true
        isUiVisible = false
        userActionTrigger = System.currentTimeMillis()
    }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var switchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val switchLine: (Boolean) -> Unit = { next ->
        val channel = playingChannel
        if (channel != null && channel.urls.size > 1) {
            showInfoOnly()
            if (next) {
                currentLineIndex = (currentLineIndex + 1) % channel.urls.size
            } else {
                currentLineIndex = (currentLineIndex - 1 + channel.urls.size) % channel.urls.size
            }
            playingUrl = channel.urls[currentLineIndex]
            com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl = playingUrl
            onChannelSelected(playingUrl)
        }
    }

    val switchChannel: (Boolean) -> Unit = { next ->
        val allCats = categories
        if (allCats.isNotEmpty()) {
            showInfoOnly()
            val catIndex = allCats.indexOf(selectedCategory)
            val channelsList = channels[selectedCategory] ?: emptyList()
            val currentIndex = channelsList.indexOf(playingChannel).takeIf { it >= 0 } ?: 0
            
            var newChannel: com.iptv.tvplayer.data.Channel? = null
            if (next) {
                if (currentIndex >= channelsList.size - 1) {
                    val nextCat = allCats[(catIndex + 1) % allCats.size]
                    selectedCategory = nextCat
                    val nextCatChannels = channels[nextCat] ?: emptyList()
                    if (nextCatChannels.isNotEmpty()) {
                        newChannel = nextCatChannels[0]
                    }
                } else {
                    newChannel = channelsList[currentIndex + 1]
                }
            } else {
                if (currentIndex <= 0) {
                    val prevCat = allCats[(catIndex - 1 + allCats.size) % allCats.size]
                    selectedCategory = prevCat
                    val prevCatChannels = channels[prevCat] ?: emptyList()
                    if (prevCatChannels.isNotEmpty()) {
                        newChannel = prevCatChannels[prevCatChannels.size - 1]
                    }
                } else {
                    newChannel = channelsList[currentIndex - 1]
                }
            }
            
            if (newChannel != null) {
                playingChannel = newChannel
                currentLineIndex = 0
                
                if (newChannel.urls.isNotEmpty()) {
                    val targetUrl = newChannel.urls[0]
                    switchJob?.cancel()
                    switchJob = coroutineScope.launch {
                        delay(600) // 600ms debounce
                        playingUrl = targetUrl
                        com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl = targetUrl
                        onChannelSelected(targetUrl)
                    }
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = isUiVisible || showSettingsMenu || showSubscriptionDialog || isInfoVisible) {
        if (showSettingsMenu) {
            showSettingsMenu = false
            keepAwake()
        } else if (showSubscriptionDialog) {
            showSubscriptionDialog = false
            keepAwake()
        } else if (isUiVisible) {
            isUiVisible = false
        } else if (isInfoVisible) {
            isInfoVisible = false
        }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val isAppLoading = channels.isEmpty() || categories.isEmpty() || categories.first() == "正在加载订阅..."

    Box(modifier = Modifier.fillMaxSize()) {
        if (isPortrait) {
        PortraitLayout(
            playerContent = playerContent,
            categories = categories,
            channels = channels,
            selectedCategory = selectedCategory,
            playingChannel = playingChannel,
            currentLineIndex = currentLineIndex,
            playingUrl = playingUrl,
            isFavorite = favorites.contains(playingUrl),
            onToggleFavorite = {
                val isAdded = com.iptv.tvplayer.data.SettingsManager.toggleFavorite(playingUrl)
                favorites = com.iptv.tvplayer.data.SettingsManager.favorites
                android.widget.Toast.makeText(context, if (isAdded) "Added to Favorites" else "Removed from Favorites", android.widget.Toast.LENGTH_SHORT).show()
            },
            onCastClick = {
            },
            onCategorySelected = { selectedCategory = it; keepAwake() },
            onChannelSelected = { ch ->
                playingChannel = ch
                currentLineIndex = 0
                if (ch.urls.isNotEmpty()) {
                    playingUrl = ch.urls[0]
                    com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl = playingUrl
                    onChannelSelected(playingUrl)
                }
                isUiVisible = false
                isInfoVisible = true
                userActionTrigger = System.currentTimeMillis()
            },
            onSettingsClick = { showSettingsMenu = true; keepAwake() },
            switchLine = switchLine,
            epgTick = epgTick
        )
    } else {
        LandscapeLayout(
            playerContent = playerContent,
            categories = categories,
            channels = channels,
            selectedCategory = selectedCategory,
            playingChannel = playingChannel,
            currentLineIndex = currentLineIndex,
            playingUrl = playingUrl,
            isUiVisible = isUiVisible,
            isInfoVisible = isInfoVisible,
            showSettingsMenu = showSettingsMenu,
            showSubscriptionDialog = showSubscriptionDialog,
            onCategorySelected = { selectedCategory = it; keepAwake() },
            onChannelSelected = { ch ->
                playingChannel = ch
                currentLineIndex = 0
                if (ch.urls.isNotEmpty()) {
                    playingUrl = ch.urls[0]
                    com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl = playingUrl
                    onChannelSelected(playingUrl)
                }
                isUiVisible = false
                isInfoVisible = true
                userActionTrigger = System.currentTimeMillis()
            },
            onSettingsClick = { showSettingsMenu = true; keepAwake() },
            keepAwake = keepAwake,
            switchLine = switchLine,
            switchChannel = switchChannel,
            epgTick = epgTick,
            showInfoOnly = showInfoOnly,
            weatherInfo = weatherInfo
        )
    }

    if (isAppLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.iptv.tvplayer.R.drawable.splash_bg),
                contentDescription = "Splash Screen",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.99f), // Keep 0.99f alpha to prevent Amlogic hardware composer from treating this layer as fully opaque!
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
    }
    
    } // Close the root Box

    // Settings Menus Overlay
    if (showSettingsMenu) {
        TvSettingsMenu(
            onDismiss = { 
                showSettingsMenu = false
                keepAwake()
            },
            onMenuSelected = { selectedMenu ->
                when (selectedMenu) {
                    "列表订阅", "EPG订阅", "UserAgent", "Headers", "咪咕账号", "油管代理" -> {
                        subscriptionDialogType = selectedMenu
                        showSettingsMenu = false
                        showSubscriptionDialog = true
                    }
                    "扫码推送" -> {
                        showSettingsMenu = false
                        (context as? com.iptv.tvplayer.MainActivity)?.let { activity ->
                            val ip = activity.getLocalIpAddress()
                            activity.showQrDialog("http://$ip:8080", "扫码推送设置")
                        }
                        keepAwake()
                    }
                    "MPV", "EXO" -> {
                        com.iptv.tvplayer.data.SettingsManager.decoderType = selectedMenu
                        showSettingsMenu = false
                        keepAwake()
                    }
                    "原始", "填充" -> {
                        com.iptv.tvplayer.data.SettingsManager.aspectRatio = selectedMenu
                        showSettingsMenu = false
                        keepAwake()
                    }
                    "5s", "10s", "15s", "20s", "25s", "30s" -> {
                        com.iptv.tvplayer.data.SettingsManager.timeoutSeconds = selectedMenu.removeSuffix("s").toInt()
                        showSettingsMenu = false
                        keepAwake()
                    }
                    "断流重连" -> {
                        com.iptv.tvplayer.data.SettingsManager.autoReconnect = !com.iptv.tvplayer.data.SettingsManager.autoReconnect
                        showSettingsMenu = false
                        keepAwake()
                    }
                }
            }
        )
    }

//    if (showSubscriptionDialog) {
//        SubscriptionDialog(
//            dialogType = subscriptionDialogType,
//            onDismiss = {
//                showSubscriptionDialog = false
//                keepAwake()
//            }
//        )
//    }
}

@Composable
fun PortraitLayout(
    playerContent: @Composable (Modifier) -> Unit,
    categories: List<String>,
    channels: Map<String, List<com.iptv.tvplayer.data.Channel>>,
    selectedCategory: String,
    playingChannel: com.iptv.tvplayer.data.Channel?,
    currentLineIndex: Int,
    playingUrl: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onCastClick: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onChannelSelected: (com.iptv.tvplayer.data.Channel) -> Unit,
    onSettingsClick: () -> Unit,
    switchLine: (Boolean) -> Unit,
    epgTick: Int
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0: 分类, 1: 频道
    val tabs = listOf("分类", "频道", "节目", "线路")

    Column(modifier = Modifier.fillMaxSize()) {
        // Player Area
        playerContent(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        // Header / Info Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* Back to Category maybe? */ activeTab = 0 }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFE91E63))
            }
            Text(
                text = selectedCategory.ifEmpty { "频道列表" },
                color = Color(0xFFE91E63),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = playingChannel?.name ?: "未选择",
                    color = Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("卫视频道", color = Color.Gray, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) androidx.compose.material.icons.Icons.Default.Favorite else androidx.compose.material.icons.Icons.Default.FavoriteBorder,
                    contentDescription = "Fav",
                    tint = Color(0xFFE91E63)
                )
            }
            IconButton(onClick = onCastClick) {
                Icon(Icons.Default.Share, contentDescription = "Cast", tint = Color(0xFFE91E63))
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = Color(0xFFE91E63))
            }
        }

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(8.dp)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = activeTab == index
                val totalLines = playingChannel?.urls?.size ?: 1
                val displayTitle = if (index == 3) "${currentLineIndex + 1}/$totalLines $title" else title
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { 
                            if (index == 3) {
                                switchLine(true)
                            } else {
                                activeTab = index 
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayTitle,
                        color = if (isSelected) Color.Black else Color.Gray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // List Content
        if (activeTab == 0) {
            // Categories List
            LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
                items(categories.size) { index ->
                    val cat = categories[index]
                    val isSelected = cat == selectedCategory
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                onCategorySelected(cat)
                                activeTab = 1 // Auto-switch to channels tab
                            }
                            .background(if (isSelected) Color(0xFFF5F5F5) else Color.White)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.Black else Color.DarkGray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                }
            }
        } else {
            // Channels List
            val currentChannels = channels[selectedCategory] ?: emptyList()
            LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
                items(currentChannels.size) { index ->
                    val channel = currentChannels[index]
                    val isPlaying = playingChannel?.name == channel.name
                    // Mock latency (fixed per channel based on hash to avoid jumping)
                    val mockPing = 30 + (channel.name.hashCode() % 100)
                    val pingColor = if (mockPing < 50) Color(0xFF4CAF50) else Color(0xFF8BC34A)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChannelSelected(channel) }
                            .background(if (isPlaying) Color(0xFFE0E0E0) else Color.White)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Channel Icon Placeholder
                        Box(
                            modifier = Modifier.size(40.dp).background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(channel.name.take(1), color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Text(
                            text = channel.name,
                            color = Color.Black,
                            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Text(
                            text = "${kotlin.math.abs(mockPing)} ms",
                            color = pingColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                }
            }
        }
    }
}

@Composable
fun LandscapeLayout(
    playerContent: @Composable (Modifier) -> Unit,
    categories: List<String>,
    channels: Map<String, List<com.iptv.tvplayer.data.Channel>>,
    selectedCategory: String,
    playingChannel: com.iptv.tvplayer.data.Channel?,
    currentLineIndex: Int,
    playingUrl: String,
    isUiVisible: Boolean,
    isInfoVisible: Boolean,
    showSettingsMenu: Boolean,
    showSubscriptionDialog: Boolean,
    onCategorySelected: (String) -> Unit,
    onChannelSelected: (com.iptv.tvplayer.data.Channel) -> Unit,
    onSettingsClick: () -> Unit,
    keepAwake: () -> Unit,
    switchLine: (Boolean) -> Unit,
    switchChannel: (Boolean) -> Unit,
    epgTick: Int,
    showInfoOnly: () -> Unit,
    weatherInfo: WeatherInfo? = null
) {
    var accumulatedDrag by remember { mutableStateOf(0f) }
    var accumulatedDragY by remember { mutableStateOf(0f) }
    var showCatchupPanel by remember { mutableStateOf(false) }
    var focusedCatchupChannel by remember { mutableStateOf<com.iptv.tvplayer.data.Channel?>(null) }
    var playingCatchupProgram by remember { mutableStateOf<com.iptv.tvplayer.data.EpgProgram?>(null) }

    // Register Activity-level key handler for 100% reliable TV remote interception.
    // This bypasses Compose's fragile focus system entirely.
    androidx.compose.runtime.DisposableEffect(
        isUiVisible, showSettingsMenu, showSubscriptionDialog, isInfoVisible, showCatchupPanel
    ) {
        com.iptv.tvplayer.KeyEventBus.onKeyDown = { keyCode ->
            // When list/settings overlay is visible, let system handle DPAD for focus navigation
            if (showCatchupPanel) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    showCatchupPanel = false
                    true
                } else false
            } else if (isUiVisible || showSettingsMenu || showSubscriptionDialog) {
                // Only intercept Menu key
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MENU -> {
                        onSettingsClick()
                        true
                    }
                    else -> false // Let Android handle DPAD focus navigation in the list
                }
            } else {
                // No overlay visible: intercept all navigation keys for channel switching
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { switchChannel(false); true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { switchChannel(true); true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { switchLine(false); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { switchLine(true); true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (isInfoVisible) {
                            keepAwake()
                        } else {
                            showInfoOnly()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MENU -> { onSettingsClick(); true }
                    else -> false
                }
            }
        }
        onDispose {
            com.iptv.tvplayer.KeyEventBus.onKeyDown = null
        }
    }

    var networkSpeed by remember { mutableStateOf("0 KB/s") }
    LaunchedEffect(Unit) {
        var lastRxBytes = android.net.TrafficStats.getTotalRxBytes()
        while(true) {
            kotlinx.coroutines.delay(1000)
            val currentRxBytes = android.net.TrafficStats.getTotalRxBytes()
            val diff = currentRxBytes - lastRxBytes
            lastRxBytes = currentRxBytes
            val speedStr = when {
                diff >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", diff / (1024f * 1024f))
                diff >= 1024 -> String.format(java.util.Locale.US, "%.1f KB/s", diff / 1024f)
                else -> "$diff B/s"
            }
            networkSpeed = speedStr
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { accumulatedDrag = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedDrag += dragAmount
                    if (accumulatedDrag > 150f) {
                        switchLine(false)
                        accumulatedDrag = 0f
                    } else if (accumulatedDrag < -150f) {
                        switchLine(true)
                        accumulatedDrag = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { accumulatedDragY = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedDragY += dragAmount
                    if (accumulatedDragY > 150f) {
                        switchChannel(false)
                        accumulatedDragY = 0f
                    } else if (accumulatedDragY < -150f) {
                        switchChannel(true)
                        accumulatedDragY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (isInfoVisible) {
                            keepAwake()
                        } else {
                            showInfoOnly()
                        }
                    },
                    onLongPress = {
                        onSettingsClick()
                    }
                )
            }
    ) {
        // Player Background
        playerContent(Modifier.fillMaxSize())

        // Network Speed Display (Bottom Left)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 24.dp)
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = networkSpeed,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Channel Info Bar (EPG) at Bottom
        AnimatedVisibility(
            visible = isInfoVisible && !showSettingsMenu && !showSubscriptionDialog && playingChannel != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val channelsList = channels[selectedCategory] ?: emptyList()
            val chIndex = channelsList.indexOf(playingChannel)
            val displayNum = if (chIndex >= 0) String.format("%03d", chIndex + 1) else "001"
            
            epgTick
            val (curProgram, nextProgram) = com.iptv.tvplayer.data.EpgManager.getCurrentAndNextProgram(playingChannel?.name ?: "")
            
            ChannelInfoBar(
                channelNumber = displayNum,
                channelName = playingChannel?.name ?: "",
                resolution = com.iptv.tvplayer.player.PlayerStateManager.videoResolution.value.takeIf { it.isNotBlank() } ?: "1920x1080",
                networkType = if (playingUrl.contains("ipv6", ignoreCase = true)) "IPV6" else "IPV4",
                currentLineIndex = currentLineIndex + 1,
                totalLines = playingChannel?.urls?.size ?: 1,
                decoderType = com.iptv.tvplayer.data.SettingsManager.decoderTypeState.value,
                curProgram = curProgram,
                nextProgram = nextProgram,
                weatherInfo = weatherInfo
            )
        }

        // FongMi Style Overlay UI
        AnimatedVisibility(
            visible = isUiVisible,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            // Auto-focus first category when list opens for TV DPAD navigation
            val firstCatFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200) // Wait for layout
                try { firstCatFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            
            Row(modifier = Modifier.fillMaxHeight().wrapContentWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.32f) // Take up ~32% of the screen horizontally
                        .background(Color(0x99000000)) // More transparent black background
                ) {
                // Left: Categories (width ~100dp)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(100.dp)
                        .padding(vertical = 16.dp)
                ) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(categories.size) { index ->
                            val cat = categories[index]
                            val isSelected = selectedCategory == cat
                            var isFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .then(if (isSelected) Modifier.focusRequester(firstCatFocusRequester) else Modifier)
                                    .focusable()
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .background(if (isFocused) Color(0x66FFFFFF) else Color.Transparent)
                                    .clickable { onCategorySelected(cat) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cat,
                                    // FongMi Style: Selected category is Yellow
                                    color = if (isSelected) Color(0xFFFFD700) else Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    
                    var isSettingsFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .focusable()
                            .onFocusChanged { isSettingsFocused = it.isFocused }
                            .background(if (isSettingsFocused) Color(0x66FFFFFF) else Color.Transparent)
                            .clickable { onSettingsClick() }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("⚙️ 设置", color = if (isSettingsFocused) Color.White else Color.Gray, fontSize = 12.sp)
                    }
                }

                // Divider
                Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0x33FFFFFF)))

                // Right: Channels
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(vertical = 16.dp, horizontal = 4.dp)
                ) {
                    if (categories.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "暂无频道",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn {
                            val currentChannels = channels[selectedCategory] ?: emptyList()
                            items(currentChannels.size) { index ->
                                val channel = currentChannels[index]
                                val isPlaying = playingChannel?.name == channel.name
                                var isFocused by remember { mutableStateOf(false) }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .focusable()
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .onKeyEvent { keyEvent ->
                                            if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && 
                                                keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                                focusedCatchupChannel = channel
                                                showCatchupPanel = true
                                                true
                                            } else false
                                        }
                                        .background(if (isFocused) Color(0x66FFFFFF) else Color.Transparent)
                                        .clickable { onChannelSelected(channel); playingCatchupProgram = null }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // FongMi Style: Channel Number (001, 002) - No Box
                                    Text(
                                        text = String.format("%03d", index + 1),
                                        color = if (isPlaying) Color(0xFFFFD700) else Color.LightGray,
                                        fontSize = 14.sp
                                    )
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    // Channel Name
                                    Text(
                                        text = channel.name, 
                                        color = if (isPlaying) Color(0xFFFFD700) else Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    if (isFocused) {
                                        Text(
                                            text = "回看 >>",
                                            color = Color(0xFF4CAF50),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } // Closes the inner Row (categories + channels)
            
            // Catchup Panel (Slides out from the right of the channel list)
            AnimatedVisibility(
                visible = showCatchupPanel,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
            ) {
                focusedCatchupChannel?.let { channel ->
                    CatchupPanel(
                        channel = channel,
                        playingProgram = playingCatchupProgram,
                        onPlayCatchup = { program, catchupUrl ->
                            playingCatchupProgram = program
                            com.iptv.tvplayer.data.SettingsManager.lastPlayedUrl = catchupUrl
                            onChannelSelected(channel.copy(urls = listOf(catchupUrl)))
                        },
                        onDismiss = { showCatchupPanel = false }
                    )
                }
            }
        }
    }
}
}

