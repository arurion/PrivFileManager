package com.privfm.explorer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.privfm.explorer.databinding.ItemFileBinding
import com.privfm.explorer.fs.FileEntry

class FileAdapter(
    private var items: List<FileEntry>,
    private val onClick: (FileEntry) -> Unit,
    private val onLongClick: (FileEntry) -> Boolean
) : RecyclerView.Adapter<FileAdapter.VH>() {

    inner class VH(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newItems: List<FileEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val icon = when {
            entry.isSymlink -> "🔗"
            entry.isDirectory -> "📁"
            else -> "📄"
        }
        holder.binding.nameView.text = "$icon ${entry.name}"
        holder.binding.metaView.text =
            "${entry.permissions}  ${entry.owner}:${entry.group}  ${entry.sizeBytes}B"
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry) }
    }

    override fun getItemCount(): Int = items.size
}
