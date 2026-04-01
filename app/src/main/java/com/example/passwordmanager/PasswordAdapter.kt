package com.example.passwordmanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.passwordmanager.data.database.PasswordEntry

class PasswordAdapter(
    private val passwords: List<PasswordEntry>,
    private val onItemClick: (PasswordEntry) -> Unit,
    private val onItemLongClick: (PasswordEntry) -> Unit
) : RecyclerView.Adapter<PasswordAdapter.PasswordViewHolder>() {

    class PasswordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvUsername: TextView = itemView.findViewById(R.id.tvUsername)
        val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PasswordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_password, parent, false)
        return PasswordViewHolder(view)
    }

    override fun onBindViewHolder(holder: PasswordViewHolder, position: Int) {
        val entry = passwords[position]
        holder.tvTitle.text = entry.title
        holder.tvUsername.text = entry.username
        holder.tvCategory.text = entry.category
        holder.itemView.setOnClickListener { onItemClick(entry) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(entry)
            true
        }
    }

    override fun getItemCount() = passwords.size
}