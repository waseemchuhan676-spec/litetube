package com.litetube.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * One-line keyword rows under the search bar (remote YouTube suggestions
 * merged with matching history entries). Same D-Pad rules as the results
 * list: each row is a single focusable unit, CENTER selects it.
 * Text only — no images, like everything else in this app.
 */
class SuggestionAdapter(
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.SuggestionViewHolder>() {

    private var items: List<String> = emptyList()

    fun submit(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.suggestionText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return SuggestionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
