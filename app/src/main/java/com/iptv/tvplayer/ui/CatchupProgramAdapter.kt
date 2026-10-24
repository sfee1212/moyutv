package com.iptv.tvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvplayer.R
import com.iptv.tvplayer.data.EpgProgram
import com.iptv.tvplayer.utils.KeyUtil

class CatchupProgramAdapter(
    private var programs: List<EpgProgram>,
    private val onProgramSelected: (EpgProgram, Int) -> Unit,
    private val onBoundaryLeft: (() -> Unit)? = null
) : RecyclerView.Adapter<CatchupProgramAdapter.ProgramViewHolder>() {

    private var playingTitle: String? = null
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class ProgramViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tv_program_time)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_program_title)
        val tvStatus: TextView = itemView.findViewById(R.id.tv_program_status)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onProgramSelected(programs[pos], pos)
                }
            }
            
            itemView.setOnKeyListener { _, _, event ->
                if (KeyUtil.isActionDown(event)) {
                    if (KeyUtil.isLeftKey(event)) {
                        onBoundaryLeft?.invoke()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_catchup_program, parent, false)
        return ProgramViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProgramViewHolder, position: Int) {
        val program = programs[position]
        holder.tvTime.text = "${program.startTimeStr} - ${program.endTimeStr}"
        holder.tvTitle.text = program.title
        
        val isPlaying = program.title == playingTitle
        val now = System.currentTimeMillis()
        
        val isLive = program.startMillis <= now && now <= program.endMillis
        val isFuture = program.startMillis > now
        val isPast = program.endMillis < now
        
        holder.tvStatus.visibility = View.VISIBLE
        
        if (isLive) {
            holder.tvTitle.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.tvStatus.text = "直播"
            holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_live)
            holder.tvStatus.setTextColor(android.graphics.Color.WHITE)
        } else if (isFuture) {
            holder.tvTitle.setTextColor(android.graphics.Color.GRAY)
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.tvStatus.text = "预约"
            holder.tvStatus.setBackgroundResource(R.drawable.bg_badge)
            holder.tvStatus.setTextColor(android.graphics.Color.GRAY)
        } else {
            holder.tvTitle.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.tvStatus.text = "回看"
            holder.tvStatus.setBackgroundResource(R.drawable.bg_badge)
            holder.tvStatus.setTextColor(android.graphics.Color.WHITE)
        }
        
        // If it's the specific program currently playing in catchup mode, highlight the title
        if (isPlaying && isPast) {
            holder.tvTitle.setTextColor(android.graphics.Color.parseColor("#00D2FF"))
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    override fun getItemCount(): Int = programs.size

    fun updateData(newPrograms: List<EpgProgram>) {
        if (programs == newPrograms) return
        programs = newPrograms
        notifyDataSetChanged()
    }
    
    fun setPlayingTitle(title: String?) {
        playingTitle = title
        notifyDataSetChanged()
    }
}
