package com.iptv.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.utils.KeyUtil

data class SettingItem(
    val name: String,
    val tag: String?,
    val isChecked: Boolean,
    val hasArrow: Boolean,
    val identifier: String // used for click handling (e.g. url or setting key)
)

class SettingsMinimalAdapter(
    private var items: List<SettingItem>,
    private val onItemSelected: (Int, SettingItem) -> Unit,
    private val onItemLongSelected: ((Int, SettingItem) -> Unit)? = null,
    private val onBoundaryKey: ((position: Int, isUp: Boolean) -> Unit)? = null
) : RecyclerView.Adapter<SettingsMinimalAdapter.SettingsViewHolder>() {

    private var selectedPosition = 0

    inner class SettingsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_setting_name)
        val tvTag: TextView = itemView.findViewById(R.id.tv_setting_tag)
        val ivCheck: TextView = itemView.findViewById(R.id.iv_setting_check)
        val ivArrow: TextView = itemView.findViewById(R.id.iv_setting_arrow)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val oldPos = selectedPosition
                    selectedPosition = pos
                    notifyItemChanged(oldPos)
                    notifyItemChanged(selectedPosition)
                    onItemSelected(pos, items[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && onItemLongSelected != null) {
                    onItemLongSelected.invoke(pos, items[pos])
                    true
                } else {
                    false
                }
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(10f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingsViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting_minimal, parent, false)
        return SettingsViewHolder(view)
    }

    override fun onBindViewHolder(holder: SettingsViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        
        if (item.tag != null) {
            holder.tvTag.text = item.tag
            holder.tvTag.visibility = View.VISIBLE
        } else {
            holder.tvTag.visibility = View.GONE
        }

        holder.ivCheck.visibility = if (item.isChecked) View.VISIBLE else View.GONE
        holder.ivArrow.visibility = if (item.hasArrow) View.VISIBLE else View.GONE

        holder.itemView.setOnKeyListener { _, _, event ->
            if (KeyUtil.isActionDown(event)) {
                if (KeyUtil.isUpKey(event) && position == 0) {
                    onBoundaryKey?.invoke(position, true)
                    return@setOnKeyListener true
                }
                if (KeyUtil.isDownKey(event) && position == items.size - 1) {
                    onBoundaryKey?.invoke(position, false)
                    return@setOnKeyListener true
                }
                if (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event)) {
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<SettingItem>, selectedPos: Int) {
        items = newItems
        selectedPosition = selectedPos
        notifyDataSetChanged()
    }
}
