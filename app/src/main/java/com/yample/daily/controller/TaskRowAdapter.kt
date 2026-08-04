package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemTaskRowBinding

class TaskRowAdapter(
    private val items: MutableList<TaskItem>,
    private val onEdit: (TaskItem) -> Unit,
    private val onDelete: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskRowAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTaskRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTaskRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvTaskTime.text = item.time
        holder.binding.tvTaskSub.text = "实际执行 ${item.actualTime ?: item.time}"
        holder.binding.tvTaskStatus.text = item.statusLabel
        val (color, bg) = statusStyle(item.status)
        holder.binding.tvTaskStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
        holder.binding.tvTaskStatus.background.setTint(ContextCompat.getColor(holder.itemView.context, bg))
        holder.binding.btnEditTask.setOnClickListener { onEdit(item) }
        holder.binding.btnDeleteTask.setOnClickListener { onDelete(item) }
    }

    private fun statusStyle(status: String): Pair<Int, Int> {
        return when (status) {
            "success" -> R.color.md_tertiary to R.color.md_tertiaryContainer
            "timeout" -> R.color.md_error to R.color.md_errorContainer
            "pending" -> R.color.md_primary to R.color.md_primaryContainer
            "skip" -> R.color.md_skip to R.color.md_skipContainer
            "expired" -> R.color.md_onSurfaceVariant to R.color.md_surfaceVariant
            else -> R.color.md_onSurfaceVariant to R.color.md_surfaceVariant
        }
    }

    override fun getItemCount() = items.size
}
