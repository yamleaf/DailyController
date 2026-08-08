package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemSettingRowBinding

class SettingAdapter(
    private val items: MutableList<SettingItem>,
    private val onToggle: (SettingItem, Boolean) -> Unit,
    private val onEditValue: (SettingItem) -> Unit
) : RecyclerView.Adapter<SettingAdapter.ViewHolder>() {

    private val iconMap = mapOf(
        "ps" to R.drawable.ic_power_save,
        "pm" to R.drawable.ic_power,
        "nc" to R.drawable.ic_device,
        "nt" to R.drawable.ic_scan,
        "fd" to R.drawable.ic_power,
        "sh" to R.drawable.ic_timer,
        "ar" to R.drawable.ic_scan,
        "rt" to R.drawable.ic_timer,
        "ga" to R.drawable.ic_device,
        "bh" to R.drawable.ic_device,
        "tm" to R.drawable.ic_timer,
        "rh" to R.drawable.ic_timer,
        "tr" to R.drawable.ic_timer,
        "ot" to R.drawable.ic_timer,
        "re" to R.drawable.ic_power
    )

    class ViewHolder(val binding: ItemSettingRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvSettingLabel.text = item.label
        val icon = iconMap[item.key]
        if (icon != null) holder.binding.imgSettingIcon.setImageResource(icon)
        else holder.binding.imgSettingIcon.setImageResource(R.drawable.ic_device)

        when (item.type) {
            "bool" -> {
                holder.binding.switchSetting.visibility = android.view.View.VISIBLE
                holder.binding.layoutValue.visibility = android.view.View.GONE
                val on = item.value as? Boolean ?: false
                // 复用 ViewHolder 时必须先摘掉旧监听，否则 isChecked 赋值会误触发上一项的回调
                holder.binding.switchSetting.setOnCheckedChangeListener(null)
                holder.binding.switchSetting.isChecked = on
                holder.binding.tvSettingSub.text = if (on) "已开启" else "已关闭"
                holder.binding.switchSetting.setOnCheckedChangeListener { _, isChecked ->
                    item.value = isChecked
                    onToggle(item, isChecked)
                }
            }
            "int" -> {
                holder.binding.switchSetting.visibility = android.view.View.GONE
                holder.binding.layoutValue.visibility = android.view.View.VISIBLE
                val v = item.value as? Int ?: 0
                holder.binding.tvSettingValue.text = "$v ${unitFor(item.key)}"
                holder.binding.tvSettingSub.text = "点击拖动调整"
                holder.binding.layoutValue.setOnClickListener { onEditValue(item) }
            }
        }
    }

    private fun unitFor(key: String): String = when (key) {
        "tm" -> "秒"
        "ot" -> "秒"
        "tr" -> "分"
        "rh" -> "时"
        "lb" -> "%"
        "bw" -> "时"
        "bs" -> "段"
        "br" -> "时"
        "bd" -> "时"
        else -> ""
    }

    override fun getItemCount() = items.size
}
