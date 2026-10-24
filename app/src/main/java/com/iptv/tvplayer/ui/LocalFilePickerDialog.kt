package com.iptv.tvplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

class LocalFilePickerDialog(private val context: Context, private val onFileSelected: (File) -> Unit) {

    private var currentDir: File
    private lateinit var dialog: AlertDialog
    private val listView = ListView(context)
    private val pathTextView = TextView(context)
    private val fileList = mutableListOf<File>()
    private lateinit var adapter: ArrayAdapter<String>

    init {
        // Start at external storage root (/storage/emulated/0) directly
        // Users can go up to /storage if they need to access USB drives and the system allows it
        currentDir = Environment.getExternalStorageDirectory()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#222222"))
        }

        pathTextView.apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(10, 10, 10, 20)
        }
        layout.addView(pathTextView)

        fun handleItemClick(position: Int) {
            val clickedStr = adapter.getItem(position)
            if (clickedStr == ".. (上一级)") {
                currentDir.parentFile?.let { 
                    currentDir = it
                    refreshList() 
                }
            } else {
                val hasUpDir = adapter.getItem(0) == ".. (上一级)"
                val fileIndex = if (hasUpDir) position - 1 else position
                if (fileIndex in fileList.indices) {
                    val file = fileList[fileIndex]
                    if (file.isDirectory) {
                        if (file.canRead()) {
                            currentDir = file
                            refreshList()
                        } else {
                            android.widget.Toast.makeText(context, "没有权限读取此文件夹", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        dialog.dismiss()
                        onFileSelected(file)
                    }
                }
            }
        }

        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 18f
                view.gravity = Gravity.CENTER_VERTICAL
                view.setPadding(20, 25, 20, 25)
                
                // Let ListView handle focus and clicks
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.isClickable = false
                
                return view
            }
        }

        listView.adapter = adapter
        listView.dividerHeight = 1
        listView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            0, 
            1.0f
        )
        
        // Android TV ListView configuration
        listView.isFocusable = true
        listView.isFocusableInTouchMode = true
        listView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        
        // Use a state list drawable for selector to show focus highlight
        val states = android.graphics.drawable.StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(Color.parseColor("#4488FF")))
        states.addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#66AAFF")))
        states.addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        listView.selector = states
        listView.setDrawSelectorOnTop(false)

        layout.addView(listView)

        dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择本地列表文件")
            .setView(layout)
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            handleItemClick(position)
        }
    }

    fun show() {
        refreshList()
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.65).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )
    }

    private fun refreshList() {
        pathTextView.text = "当前路径: ${currentDir.absolutePath}"
        fileList.clear()
        
        val files = currentDir.listFiles()?.filter {
            it.isDirectory || it.name.endsWith(".m3u", true) || 
            it.name.endsWith(".m3u8", true) || it.name.endsWith(".txt", true)
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.toLowerCase(java.util.Locale.ROOT) }))
        
        if (files != null) {
            fileList.addAll(files)
        }

        adapter.clear()
        if (currentDir.absolutePath != "/" && currentDir.parentFile != null) {
            adapter.add(".. (上一级)")
        }
        
        fileList.forEach {
            val prefix = if (it.isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 "
            adapter.add(prefix + it.name)
        }
        adapter.notifyDataSetChanged()
        
        if (adapter.count > 0) {
            listView.post {
                listView.setSelection(0)
                listView.requestFocus()
            }
        }
    }
}

