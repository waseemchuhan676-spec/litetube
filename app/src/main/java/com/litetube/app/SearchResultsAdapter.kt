package com.litetube.app

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
 *
 * The three-dots button is the one extra focus stop per row (VidMate-style
 * download menu): RIGHT from the row reaches it (the row's
 * nextFocusRight points at it and is resolved inside this row's own
 * subtree, so duplicate ids across rows are safe), LEFT on the button
 * returns to the row (handled in code below, because the button cannot
 * resolve its parent row by id — findViewById only searches downwards).
 * UP/DOWN from the button fall through to the list's normal geometric
 * row-to-row movement.
 */
class SearchResultsAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onMenuClick: (VideoItem, View) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    private var items: List<VideoItem> = emptyList()

    fun submit(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.resultTitle)
        val meta: TextView = view.findViewById(R.id.resultMeta)
        val menuButton: ImageButton = view.findViewById(R.id.rowMenuButton)
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
        holder.menuButton.setOnClickListener { onMenuClick(item, it) }
        // D-Pad: RIGHT on the row moves into its own ⋮ button (the XML
        // nextFocusRight is the same route; this is the deterministic
        // fallback). LEFT on the button returns to the row.
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                holder.menuButton.requestFocus()
                true
            } else {
                false
            }
        }
        holder.menuButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                holder.itemView.requestFocus()
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
