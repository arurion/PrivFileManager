// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ItemAppBinding
import com.privfm.explorer.fs.DebuggableApp

class AppAdapter(
    private var items: List<DebuggableApp>,
    private val packageManager: PackageManager,
    private val onClick: (DebuggableApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newItems: List<DebuggableApp>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.binding.appLabelView.text = app.label
        holder.binding.appPackageView.text = "${app.packageName}  (${app.dataDir})"
        val icon = try {
            packageManager.getApplicationIcon(app.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (icon != null) holder.binding.appIconView.setImageDrawable(icon)
        else holder.binding.appIconView.setImageResource(R.drawable.ic_file)
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = items.size
}
