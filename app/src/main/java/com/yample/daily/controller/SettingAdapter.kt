package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        "sm" to R.drawable.ic_power,
        "nc" to R.drawable.ic_device,
        "nt" to R.drawable.ic_scan,
        "fd" to R.drawable.ic_power,
        "sh" to R.drawable.ic_timer,
        "uh" to R.drawable.ic_timer,
        "cw" to R.drawable.ic_timer,
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
                val enabled = setting.writable
                vh.binding.tvSettingLabel.text = setting.label
                vh.binding.root.alpha = if (enabled) 1f else 0.45f
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
                        vh.binding.switchSetting.isEnabled = enabled
                        vh.binding.tvSettingSub.text = when {
                            setting.key == "uh" -> "点击触发重新拉取"
                            on -> "已开启"
                            else -> "已关闭"
                        }
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
                        if (enabled) {
                            vh.binding.layoutValue.setOnClickListener { onEditValue(setting) }
                        } else {
                            vh.binding.layoutValue.setOnClickListener(null)
                            vh.binding.layoutValue.isClickable = false
                        }
                    }
                    "int" -> {
                        vh.binding.switchSetting.visibility = View.GONE
                        vh.binding.layoutValue.visibility = View.VISIBLE
                        val v = setting.value as? Int ?: 0
                        if (setting.key == "sm") {
                            vh.binding.tvSettingValue.text = screenModeLabel(v)
                            vh.binding.tvSettingSub.text =
                                if (enabled) "点击切换屏幕模式" else "伪息屏开启时由伪息屏策略接管，不可修改"
                        } else {
                            // 兼容旧被控端仍把 bw 标成 int：按当日时间点展示
                            if (setting.key == "bw") {
                                vh.binding.tvSettingValue.text =
                                    String.format("%02d:%02d", v / 60, v % 60)
                                vh.binding.tvSettingSub.text = "点击选择时间"
                            } else {
                                vh.binding.tvSettingValue.text = "$v ${unitFor(setting.key)}"
                                vh.binding.tvSettingSub.text = "点击拖动调整"
                            }
                        }
                        // 注意：setOnClickListener 会把 clickable 置回 true，禁用时必须清 listener
                        if (enabled) {
                            vh.binding.layoutValue.setOnClickListener { onEditValue(setting) }
                        } else {
                            vh.binding.layoutValue.setOnClickListener(null)
                            vh.binding.layoutValue.isClickable = false
                        }
                    }
                    "string" -> {
                        vh.binding.switchSetting.visibility = View.GONE
                        vh.binding.layoutValue.visibility = View.VISIBLE
                        if (setting.key == "cw") {
                            // 自定义工作日：用 7 个圆点直观展示（实心=工作日，空心=非工作日），点击弹出星期多选
                            vh.binding.tvSettingValue.visibility = View.GONE
                            vh.binding.layoutWorkdayDots.visibility = View.VISIBLE
                            renderWorkdayDots(
                                vh.binding.layoutWorkdayDots,
                                setting.value?.toString().orEmpty()
                            )
                            vh.binding.tvSettingSub.text = "点击设置工作日"
                        } else {
                            vh.binding.tvSettingValue.visibility = View.VISIBLE
                            vh.binding.tvSettingValue.text = setting.value?.toString().orEmpty()
                            vh.binding.layoutWorkdayDots.visibility = View.GONE
                            vh.binding.tvSettingSub.text = ""
                        }
                        if (enabled) {
                            vh.binding.layoutValue.setOnClickListener { onEditValue(setting) }
                        } else {
                            vh.binding.layoutValue.setOnClickListener(null)
                            vh.binding.layoutValue.isClickable = false
                        }
                    }
                }
            }
        }
    }

    private fun screenModeLabel(v: Int): String = when (v) {
        1 -> "息屏"
        2 -> "常亮"
        else -> "伪息屏"
    }

    /** 周一~周日 顺序，与圆点位置一一对应（1=周一 ... 7=周日） */
    private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 把工作日串渲染成一行 7 个圆点：实心=工作日，空心=非工作日 */
    private fun renderWorkdayDots(container: ViewGroup, raw: String) {
        val selected = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        container.removeAllViews()
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val dotSize = (14 * density).toInt() // 直径 14dp
        val gap = (8 * density).toInt()      // 间距 8dp
        for (i in 0..6) {
            val dot = View(ctx)
            val lp = LinearLayout.LayoutParams(dotSize, dotSize)
            if (i > 0) lp.marginStart = gap
            dot.layoutParams = lp
            val isWorkday = (i + 1) in selected
            dot.background = ContextCompat.getDrawable(
                ctx,
                if (isWorkday) R.drawable.dot_filled else R.drawable.dot_hollow
            )
            dot.contentDescription =
                WEEKDAY_LABELS[i] + if (isWorkday) "（工作日）" else "（非工作日）"
            container.addView(dot)
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