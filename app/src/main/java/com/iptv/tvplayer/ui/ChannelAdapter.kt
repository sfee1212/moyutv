package com.iptv.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.data.Channel
import com.iptv.tvplayer.utils.KeyUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelSelected: (Channel, Int) -> Unit,
    private val onChannelFocused: ((Channel, Boolean) -> Unit)? = null,
    private val onBoundaryKey: ((position: Int, isUp: Boolean) -> Unit)? = null,
    private val onChannelLongPressed: ((Channel, Int) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedPosition = -1
    private var recyclerView: RecyclerView? = null
    private var startNumber = 1
    // 收藏频道名快照, 避免每次 bind 都读 SharedPreferences
    private var favoriteNames: Set<String> =
        com.iptv.tvplayer.data.SettingsManager.favoriteChannels.keys

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNumber: TextView = itemView.findViewById(R.id.tv_channel_number)
        val tvName: TextView = itemView.findViewById(R.id.tv_channel_name)
        val tvEpg: TextView = itemView.findViewById(R.id.tv_channel_epg)
        val ivLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelSelected(channels[pos], pos)
                }
            }
            // 长按 (手机触摸 / 电视遥控器按住OK键) 收藏或取消收藏
            itemView.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                    onChannelLongPressed?.invoke(channels[pos], pos)
                    true
                } else {
                    false
                }
            }
            // Removed setOnFocusChangeListener to prevent overwriting the selected (playing) state
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.tvNumber.text = String.format("%04d", position + startNumber)
        holder.tvNumber.visibility = if (com.iptv.tvplayer.data.SettingsManager.showChannelNumber) android.view.View.VISIBLE else android.view.View.GONE
        
        val cleanName = channel.name.replace(Regex("^\\d+\\.\\s*"), "")
        if (favoriteNames.contains(channel.name)) {
            // 收藏星标: 仅 ★ 字符染成黄色, 频道名保持原色
            val span = android.text.SpannableStringBuilder("★ $cleanName")
            span.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.YELLOW),
                0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.tvName.text = span
        } else {
            holder.tvName.text = cleanName
        }
        holder.itemView.isSelected = position == selectedPosition
        
        val logoUrl = channel.logo ?: com.iptv.tvplayer.data.EpgManager.getChannelLogo(channel.name)
        if (com.iptv.tvplayer.data.SettingsManager.showChannelLogo &&
            logoUrl != null && logoUrl.isNotEmpty()) {
            ImageLoader.load(logoUrl, holder.ivLogo)
        } else {
            holder.ivLogo.tag = null
            holder.ivLogo.setImageBitmap(null)
            holder.ivLogo.visibility = View.GONE
        }


        val epgInfo = com.iptv.tvplayer.data.EpgManager.getCurrentAndNextProgram(channel.name)
        if (epgInfo.first != null) {
            holder.tvEpg.text = "${epgInfo.first!!.startTimeStr} ${epgInfo.first!!.title}"
        } else {
            holder.tvEpg.text = "精彩节目"
        }

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onChannelFocused?.invoke(channels[pos], hasFocus)
            }
        }

        holder.itemView.setOnKeyListener { _, _, event ->
            if (KeyUtil.isActionDown(event)) {
                if (KeyUtil.isUpKey(event) && position == 0) {
                    onBoundaryKey?.invoke(position, true)
                    return@setOnKeyListener true
                }
                if (KeyUtil.isDownKey(event) && position == channels.size - 1) {
                    onBoundaryKey?.invoke(position, false)
                    return@setOnKeyListener true
                }
                if (KeyUtil.isRightKey(event)) {
                    // Let activity handle this for the vertical catchup tab
                    return@setOnKeyListener false
                }
            }
            false
        }
    }

    override fun getItemCount(): Int = channels.size

    fun updateData(newChannels: List<Channel>, startNum: Int = 1) {
        preserveFocusAcrossRefresh {
            channels = newChannels
            startNumber = startNum
            selectedPosition = -1
            notifyDataSetChanged()
        }
    }

    /** 收藏变化后刷新星标显示 */
    fun refreshFavorites() {
        preserveFocusAcrossRefresh {
            favoriteNames = com.iptv.tvplayer.data.SettingsManager.favoriteChannels.keys
            notifyDataSetChanged()
        }
    }

    /**
     * 执行数据刷新并在布局结束后检查焦点:
     * 刷新期间原焦点行被回收时, 系统会把焦点退给窗口内第一个可聚焦项(分类栏第一项),
     * 导致"打开节目列表后焦点不在当前播放节目". 若频道栏丢失了此前的子项焦点则恢复原位.
     */
    private fun preserveFocusAcrossRefresh(block: () -> Unit) {
        val rv = recyclerView
        if (rv == null) {
            block()
            return
        }
        val focusedChild = rv.focusedChild
        val oldFocusPos = focusedChild?.let { rv.getChildAdapterPosition(it) }
            ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
        block()
        if (focusedChild == null || channels.isEmpty()) return
        val target = if (oldFocusPos in 0 until channels.size) oldFocusPos else 0
        rv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!rv.hasFocus()) {
                    val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    val child = lm?.findViewByPosition(target)
                    if (child == null || !child.requestFocus()) {
                        rv.requestFocus()
                    }
                }
            }
        })
    }

    fun setSelected(position: Int) {
        val oldPos = selectedPosition
        selectedPosition = position
        recyclerView?.let { rv ->
            if (oldPos != -1) {
                rv.findViewHolderForAdapterPosition(oldPos)?.itemView?.isSelected = false
            }
            if (selectedPosition != -1) {
                rv.findViewHolderForAdapterPosition(selectedPosition)?.itemView?.isSelected = true
            }
        }
    }

    fun getSelectedPosition(): Int = selectedPosition
}

object ImageLoader {
    private val cache = HashMap<String, android.graphics.Bitmap>()

    fun load(url: String, imageView: android.widget.ImageView) {
        imageView.tag = url
        val cached = cache[url]
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            return
        }

        imageView.setImageBitmap(null)
        imageView.visibility = View.GONE

        MainScope().launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val resolvedUrl = com.iptv.tvplayer.proxy.ProxyManager.resolveUrl(url)
                    val conn = java.net.URL(resolvedUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.doInput = true
                    conn.connect()
                    if (conn.responseCode == 200) {
                        android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                cache[url] = bitmap
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                }
            }
        }
    }
}
