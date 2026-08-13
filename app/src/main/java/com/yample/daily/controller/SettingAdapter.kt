package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemSettingRowBinding

sealed class SettingListItem {
    data class Header(val title: String) : SettingListItem()
    data class Item(val setting: SettingItem) : SettingListItem()
}

class SettingAdapter(
    private val items: MutableList<SettingListItem>,
    private val onToggle: (SettingItem, Boolean) -> Unit,
    private val onEditValue: (SettingItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

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
        "re" to R.drawable.ic_power,
        "lb" to R.drawable.ic_power_save,
        "ba" to R.drawable.ic_power_save,
        "bw" to R.drawable.ic_timer,
        "bs" to R.drawable.ic_timer,
        "br" to R.drawable.ic_timer,
        "bd" to R.drawable.ic_timer,
        "bo" to R.drawable.ic_timer,
        "dp" to R.drawable.ic_device,
        "lg" to R.drawable.ic_scan
    )

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class ItemViewHolder(val binding: ItemSettingRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SettingListItem.Header -> TYPE_HEADER
            is SettingListItem.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_setting_section, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val binding = ItemSettingRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                ItemViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingListItem.Header -> {
                (holder as HeaderViewHolder).itemView.findViewById<TextView>(R.id.tvSectionHeader).text = item.title
            }
            is SettingListItem.Item -> {
                val vh = holder as ItemViewHolder
                val setting = item.setting
                vh.binding.tvSettingLabel.text = setting.label
                val icon = iconMap[setting.key]
                if (icon != null) vh.binding.imgSettingIcon.setImageResource(icon)
                else vh.binding.imgSettingIcon.setImageResource(R.drawable.ic_device)

                when (setting.type) {
                    "bool" -> {
                        vh.binding.switchSetting.visibility = View.VISIBLE
                        vh.binding.layoutValue.visibility = View.GONE
                        val on = setting.value as? Boolean ?: false
                        vh.binding.switchSetting.setOnCheckedChangeListener(null)
                        vh.binding.switchSetting.isChecked = on
                        vh.binding.tvSettingSub.text = if (on) "已开启" else "已关闭"
                        vh.binding.switchSetting.setOnCheckedChangeListener { _, isChecked ->
                            setting.value = isChecked
                            onToggle(setting, isChecked)
                        }
                    }
                    "time" -> {
                        vh.binding.switchSetting.visibility = View.GONE
                        vh.binding.layoutValue.visibility = View.VISIBLE
                        val minutes = (setting.value as? Int) ?: 0
                        vh.binding.tvSettingValue.text =
                            String.format("%02d:%02d", minutes / 60, minutes % 60)
                        vh.binding.tvSettingSub.text = "点击选择时间"
                        vh.binding.layoutValue.setOnClickListener { onEditValue(setting) }
                    }
                    "int" -> {
                        vh.binding.switchSetting.visibility = View.GONE
                        vh.binding.layoutValue.visibility = View.VISIBLE
                        val v = setting.value as? Int ?: 0
                        // 兼容旧被控端仍把 bw 标成 int：按当日时间点展示
                        if (setting.key == "bw") {
                            vh.binding.tvSettingValue.text =
                                String.format("%02d:%02d", v / 60, v % 60)
                            vh.binding.tvSettingSub.text = "点击选择时间"
                        } else {
                            vh.binding.tvSettingValue.text = "$v ${unitFor(setting.key)}"
                            vh.binding.tvSettingSub.text = "点击拖动调整"
                        }
                        vh.binding.layoutValue.setOnClickListener { onEditValue(setting) }
                    }
                }
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