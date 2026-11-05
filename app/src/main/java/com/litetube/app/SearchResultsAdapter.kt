package com.litetube.app

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
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
 * Search results adapter. Each video is a vertical CARD (v1.6.0,
 * VidMate reference): a large 16:9 thumbnail on top, title + meta
 * below, and a download icon + ⋮ button on the meta line. The whole
 * card is one focusable unit for D-Pad; the download icon and the ⋮
 * button are two extra focus stops per card.
 *
 * Focus chain per card: card --RIGHT--> download button --RIGHT-->
 * ⋮ button; LEFT walks back the same way (handled in code below,
 * because a button cannot resolve its parent card by id —
 * findViewById only searches downwards). UP/DOWN fall through to the
 * list's normal geometric card-to-card movement.
 *
 * Thumbnails (v1.4.0, 16:9 cards in v1.6.0): each card shows the
 * video's 16:9 thumbnail (usually mqdefault.jpg, 320x180), loaded by
 * the shared [thumbnailLoader] (one 4 MB cache for both lists). The
 * loader tags the ImageView with the URL, so a recycled card never
 * shows a stale image; an empty/failed thumbnail leaves the
 * light-grey placeholder background visible.
 */
class SearchResultsAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onMenuClick: (VideoItem, View) -> Unit,
    private val onDownloadClick: (VideoItem) -> Unit,
    private val thumbnailLoader: ThumbnailLoader
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    private var items: List<VideoItem> = emptyList()

    fun submit(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.resultThumbnail)
        val title: TextView = view.findViewById(R.id.resultTitle)
        val meta: TextView = view.findViewById(R.id.resultMeta)
        val downloadButton: ImageButton = view.findViewById(R.id.rowDownloadButton)
        val menuButton: ImageButton = view.findViewById(R.id.rowMenuButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val item = items[position]
        thumbnailLoader.load(item.thumbnailUrl, holder.thumbnail)
        holder.title.text = item.title
        val duration = formatDuration(item.durationSeconds)
        holder.meta.text = if (duration.isEmpty()) {
            item.uploader
        } else {
            "${item.uploader} • $duration"
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.downloadButton.setOnClickListener { onDownloadClick(item) }
        holder.menuButton.setOnClickListener { onMenuClick(item, it) }
        // D-Pad: RIGHT walks card -> download icon -> ⋮ button (the XML
        // nextFocusRight is the same route; this is the deterministic
        // fallback). LEFT walks back the same way.
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                holder.downloadButton.requestFocus()
                true
            } else {
                false
            }
        }
        holder.downloadButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    holder.menuButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    holder.itemView.requestFocus()
                    true
                }
                else -> false
            }
        }
        holder.menuButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                holder.downloadButton.requestFocus()
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
