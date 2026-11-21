package com.yample.daily.controller

import android.content.res.ColorStateList
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
        // 备注独立成行展示（无备注时隐藏），实际执行时间固定占一行
        holder.binding.tvTaskName.text = item.name
        holder.binding.tvTaskName.visibility = if (item.name.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.tvTaskSub.text = "实际执行 ${item.actualTime ?: item.time}"
        holder.binding.tvTaskStatus.text = item.statusLabel
        val (color, bg, onBg) = statusStyle(item.status)
        val ctx = holder.itemView.context
        holder.binding.tvTaskStatus.setTextColor(ContextCompat.getColor(ctx, color))
        holder.binding.tvTaskStatus.background.setTint(ContextCompat.getColor(ctx, bg))
        // 链式节点：圆底背景按状态染色 + 图标 tint 对齐容器对比色
        holder.binding.chainDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
        holder.binding.imgTaskIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, onBg))
        holder.binding.btnEditTask.setOnClickListener { onEdit(item) }
        holder.binding.btnDeleteTask.setOnClickListener { onDelete(item) }
    }

    private fun statusStyle(status: String): Triple<Int, Int, Int> {
        return when (status) {
            "success" -> Triple(R.color.md_tertiary, R.color.md_tertiaryContainer, R.color.md_onTertiaryContainer)
            "timeout" -> Triple(R.color.md_warning, R.color.md_warningContainer, R.color.md_onWarningContainer)
            "pending" -> Triple(R.color.md_primary, R.color.md_primaryContainer, R.color.md_onPrimaryContainer)
            "skip" -> Triple(R.color.md_skip, R.color.md_skipContainer, R.color.md_onSurfaceVariant)
            "expired" -> Triple(R.color.md_onSurfaceVariant, R.color.md_surfaceVariant, R.color.md_onSurfaceVariant)
            else -> Triple(R.color.md_onSurfaceVariant, R.color.md_surfaceVariant, R.color.md_onSurfaceVariant)
        }
    }

    override fun getItemCount() = items.size
}
