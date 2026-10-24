package com.iptv.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.utils.KeyUtil

class CategoryAdapter(
    private val categories: List<String>,
    private val onCategorySelected: (Int) -> Unit,
    private val onBoundaryKey: ((position: Int, isUp: Boolean) -> Unit)? = null
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var selectedPosition = 0
    private var recyclerView: RecyclerView? = null

    /** 最近一次分类切换是否由焦点移动触发 (点击=false, 焦点=true), 供上层校验焦点归属 */
    var selectionByFocus: Boolean = false
        private set

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_category_name)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    setSelected(pos)
                    selectionByFocus = false
                    onCategorySelected(pos)
                }
            }
            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val oldPos = selectedPosition
                        selectedPosition = pos
                        recyclerView?.let { rv ->
                            rv.findViewHolderForAdapterPosition(oldPos)?.itemView?.isSelected = false
                        }
                        view.isSelected = true
                        selectionByFocus = true
                        onCategorySelected(pos)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.tvName.text = categories[position]
        holder.itemView.isSelected = position == selectedPosition

        holder.itemView.setOnKeyListener { _, _, event ->
            if (KeyUtil.isActionDown(event)) {
                if (KeyUtil.isUpKey(event) && position == 0) {
                    onBoundaryKey?.invoke(position, true)
                    return@setOnKeyListener true
                }
                if (KeyUtil.isDownKey(event) && position == categories.size - 1) {
                    onBoundaryKey?.invoke(position, false)
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    override fun getItemCount(): Int = categories.size

    fun setSelected(position: Int) {
        val oldPos = selectedPosition
        selectedPosition = position
        recyclerView?.let { rv ->
            rv.findViewHolderForAdapterPosition(oldPos)?.itemView?.isSelected = false
            if (selectedPosition != -1) {
                rv.findViewHolderForAdapterPosition(selectedPosition)?.itemView?.isSelected = true
            }
        }
    }
}
