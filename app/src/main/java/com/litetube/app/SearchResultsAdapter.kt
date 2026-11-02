package com.litetube.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Formats seconds as H:MM:SS (or M:SS under one hour) for result rows. */
fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Search results adapter. Each row is a single focusable unit (see
 * item_result.xml) so D-Pad users move row-to-row with UP/DOWN and select
 * with CENTER — no nested focusable children to get trapped in.
 */
class SearchResultsAdapter(
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    private var items: List<VideoItem> = emptyList()

    fun submit(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.resultTitle)
        val meta: TextView = view.findViewById(R.id.resultMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val duration = formatDuration(item.durationSeconds)
        holder.meta.text = if (duration.isEmpty()) {
            item.uploader
        } else {
            "${item.uploader} • $duration"
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
