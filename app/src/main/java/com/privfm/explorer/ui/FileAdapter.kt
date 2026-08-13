// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ItemFileBinding
import com.privfm.explorer.fs.FileEntry

/**
 * ファイル一覧アダプタ。
 * 絵文字ではなくベクタードロアブルでアイコンを表示し、複数選択モードに対応する。
 * (デザイン意図: AOSP DocumentsUIのような「アイコン+2行テキスト」のリスト行に倣う)
 */
class FileAdapter(
    private var items: List<FileEntry>,
    private val onClick: (FileEntry) -> Unit,
    private val onLongClick: (FileEntry) -> Boolean,
    private val isSelectionMode: () -> Boolean = { false },
    private val isSelected: (FileEntry) -> Boolean = { false },
    private val onToggleSelect: (FileEntry) -> Unit = {}
) : RecyclerView.Adapter<FileAdapter.VH>() {

    inner class VH(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newItems: List<FileEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun currentList(): List<FileEntry> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]

        val iconRes = when {
            entry.isSymlink -> R.drawable.ic_symlink
            entry.isDirectory -> R.drawable.ic_folder
            else -> R.drawable.ic_file
        }
        holder.binding.iconView.setImageResource(iconRes)

        holder.binding.nameView.text = entry.name
        val sizeLabel = if (entry.isDirectory) "" else " ${humanReadableSize(entry.sizeBytes)}"
        holder.binding.metaView.text = "${entry.permissions} ${entry.owner}:${entry.group}$sizeLabel"

        val selectionMode = isSelectionMode()
        holder.binding.selectCheckbox.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.selectCheckbox.setOnCheckedChangeListener(null)
        holder.binding.selectCheckbox.isChecked = isSelected(entry)
        holder.binding.selectCheckbox.setOnCheckedChangeListener { _, _ -> onToggleSelect(entry) }

        holder.itemView.setOnClickListener {
            if (selectionMode) onToggleSelect(entry) else onClick(entry)
        }
        holder.itemView.setOnLongClickListener { onLongClick(entry) }
    }

    override fun getItemCount(): Int = items.size

    private fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex < 0) "${bytes}B" else String.format("%.1f%s", value, units[unitIndex])
    }
}
