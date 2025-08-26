package com.example.rhdtool.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rhdtool.R

class DipAdapter : ListAdapter<String, DipVH>(object : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem === newItem
    override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DipVH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dip, parent, false) as TextView
        return DipVH(tv)
    }
    override fun onBindViewHolder(holder: DipVH, position: Int) {
        holder.bind(getItem(position))
    }
}

class DipVH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
    fun bind(text: String) {
        tv.text = text
    }
}
