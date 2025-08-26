package com.example.rhdtool.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rhdtool.R
import com.example.rhdtool.data.Event
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventAdapter :
    ListAdapter<Event, EventAdapter.EventViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val eventText: TextView = itemView.findViewById(R.id.eventText)
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(event: Event) {
            val time = timeFormat.format(Date(event.timestamp))
            val text = "[$time] ${event.type}: ${event.status}"
            eventText.text = text

            // 🔹 Set color depending on type
            when (event.type.lowercase(Locale.getDefault())) {
                "wifi" -> eventText.setTextColor(Color.MAGENTA) // Violet
                "mobile" -> eventText.setTextColor(Color.RED)    // Red
                "battery" -> eventText.setTextColor(Color.GREEN) // Green
                else -> eventText.setTextColor(Color.WHITE)      // Default
            }
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem == newItem
        }
    }
}
