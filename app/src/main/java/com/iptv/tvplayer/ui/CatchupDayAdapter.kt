package com.iptv.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.utils.KeyUtil

class CatchupDayAdapter(
    private var days: List<String>,
    private val onDaySelected: (String, Int) -> Unit,
    private val onBoundaryRight: (() -> Unit)? = null
) : RecyclerView.Adapter<CatchupDayAdapter.DayViewHolder>() {

    private var selectedPosition = 0
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDayName: TextView = itemView.findViewById(R.id.tv_day_name)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDaySelected(days[pos], pos)
                }
            }
            
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onDaySelected(days[pos], pos)
                    }
                }
            }
            
            itemView.setOnKeyListener { _, _, event ->
                if (KeyUtil.isActionDown(event)) {
                    if (KeyUtil.isRightKey(event)) {
                        onBoundaryRight?.invoke()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_catchup_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        val day = days[position]
        holder.tvDayName.text = day.split(" ").first() // "今天 07-01" -> "今天"
        
        holder.itemView.isSelected = position == selectedPosition
        if (position == selectedPosition) {
            holder.tvDayName.setTextColor(android.graphics.Color.parseColor("#FFD700"))
            holder.tvDayName.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            holder.tvDayName.setTextColor(android.graphics.Color.WHITE)
            holder.tvDayName.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    override fun getItemCount(): Int = days.size

    fun updateData(newDays: List<String>) {
        if (days == newDays) {
            val oldPos = selectedPosition
            selectedPosition = 0
            if (oldPos != 0) {
                notifyItemChanged(oldPos)
                notifyItemChanged(0)
            }
            return
        }
        days = newDays
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        val oldPos = selectedPosition
        selectedPosition = position
        recyclerView?.let { rv ->
            if (oldPos != -1) {
                rv.findViewHolderForAdapterPosition(oldPos)?.let {
                    it.itemView.isSelected = false
                    val tv = it.itemView.findViewById<TextView>(R.id.tv_day_name)
                    tv?.setTextColor(android.graphics.Color.WHITE)
                    tv?.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
            if (selectedPosition != -1) {
                rv.findViewHolderForAdapterPosition(selectedPosition)?.let {
                    it.itemView.isSelected = true
                    val tv = it.itemView.findViewById<TextView>(R.id.tv_day_name)
                    tv?.setTextColor(android.graphics.Color.parseColor("#FFD700"))
                    tv?.setTypeface(null, android.graphics.Typeface.BOLD)
                }
            }
        }
    }
}
