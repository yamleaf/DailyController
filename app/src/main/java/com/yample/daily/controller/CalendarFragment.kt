package com.yample.daily.controller

import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.FragmentCalendarBinding
import com.yample.daily.controller.databinding.ItemCalendarDayBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val days = mutableListOf<CalendarDay>()
    private val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    private lateinit var calendarAdapter: CalendarAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        calendarAdapter = CalendarAdapter(days, todayStr) { isAdded && _binding != null }
        binding.rvCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        binding.rvCalendar.adapter = calendarAdapter
        bindChipHelp(binding.tvCalendarSummary, "打卡统计口径") {
            "「已打卡」＝日历中状态为成功或超时的天数合计（超时也算执行过，只是确认结果回来得晚）。\n" +
                "「计划」＝被控端上报的计划执行天数，休息/跳过不计入。\n" +
                "「今日」＝被控端上报的今日状态原文。\n\n" +
                "统计范围为本页日历（近两周 + 未来两周），数据来自 DT 快照，离线时显示缓存值。"
        }
        bindChipHelp(binding.layoutCalendarLegend, "日历图例") {
            "格子里的符号：✓ 成功打卡、⚠ 超时（已执行但确认偏晚）、❖ 待执行/已计划、× 未打卡、休 休息日、跳 主动跳过。\n\n" +
                "今天带描边；颜色与下方图例的圆点一一对应。轻点任意日期格子可查看该天的具体状态。"
        }
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        binding.tvCalendarSummary.text = "已打卡 ${s.calendar.punched} 天 / 计划 ${s.calendar.scheduled} 天 / 今日：${s.calendar.today}"
        days.clear()

        // 用空占位把首列对齐到「星期一」表头，保证日期与真实星期在同一列
        val firstWeekday = s.calendar.days.firstOrNull()?.weekday ?: 1
        val offset = (firstWeekday - 1).coerceIn(0, 6)
        repeat(offset) {
            days.add(CalendarDay(date = "", weekday = 0, status = "none", label = ""))
        }
        days.addAll(s.calendar.days)
        // 尾部补位到整周（7 的倍数），与状态查询邮件一致：网格永远铺满、不显空旷
        val trailing = (7 - (days.size % 7)) % 7
        repeat(trailing) {
            days.add(CalendarDay(date = "", weekday = 0, status = "none", label = ""))
        }
        calendarAdapter.notifyDataSetChanged()
        renderHistory(s)
    }

    /** 需求 6：渲染最近打卡记录（由概览页迁移至日历页） */
    private fun renderHistory(s: DeviceSnapshot) {
        binding.layoutHistory.removeAllViews()
        if (s.history.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "近 14 天无打卡记录"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
                setPadding(0, 12, 0, 4)
            }
            binding.layoutHistory.addView(empty)
            return
        }
        s.history.forEach { h ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = h.time
            row.tvRowValue.text = h.result
            row.tvRowValue.setTextColor(historyColor(h.result))
            row.root.bindHelp(
                title = "打卡记录",
                message = {
                    "时间：${h.time}\n结果：${h.result}\n\n" +
                        "这里是被控端上报的近 14 天打卡流水。「超时」表示任务执行过但结果确认偏晚，仍计入已打卡天数。"
                },
                alive = { isAdded && _binding != null }
            )
            binding.layoutHistory.addView(row.root)
        }
    }

    /** 信息条点击说明：委托 [bindHelp] 公共实现；isAdded 守卫防止页面已销毁后弹窗抛异常。 */
    private fun bindChipHelp(view: View, title: String, message: () -> String) {
        view.bindHelp(title, message) { isAdded && _binding != null }
    }

    private fun historyColor(result: String): Int {
        return requireContext().getColor(when {
            result.contains("成功") -> R.color.md_tertiary
            result.contains("超时") -> R.color.md_error
            else -> R.color.md_onSurface
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class CalendarAdapter(
        private val items: List<CalendarDay>,
        private val todayStr: String,
        private val alive: () -> Boolean
    ) : RecyclerView.Adapter<CalendarAdapter.VH>() {
        class VH(val binding: ItemCalendarDayBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            if (item.status == "none" && item.label.isBlank()) {
                holder.binding.tvDay.text = ""
                holder.binding.tvDay.background.setTint(ctx.getColor(android.R.color.transparent))
                holder.binding.tvDay.setTextColor(ctx.getColor(R.color.md_onSurfaceVariant))
                // ViewHolder 复用：占位格必须清掉上一次绑定的点击态与 ripple，否则空白格也能弹说明
                holder.binding.tvDay.setOnClickListener(null)
                holder.binding.tvDay.isClickable = false
                holder.binding.tvDay.foreground = null
                return
            }
            val isToday = item.date == todayStr
            val (bg, fg) = dayStyle(item.status)
            val glyph = dayGlyph(item.status)
            holder.binding.tvDay.bindHelp(
                title = "日历：${item.date}",
                message = {
                    "日期：${item.date}${if (isToday) "（今天）" else ""}\n" +
                        "状态：${dayDesc(item.status)}\n符号：$glyph\n\n" +
                        "日历口径：✓ 成功、⚠ 超时、❖ 待执行/已计划、× 未打卡、休 休息日、跳 主动跳过。\n" +
                        "数据来自 DT 快照，离线时显示的是缓存值。"
                },
                alive = alive
            )
            holder.binding.tvDay.text = "${item.label}\n$glyph"
            // 始终使用状态对应的前景色
            holder.binding.tvDay.setTextColor(ctx.getColor(fg))
            if (isToday) {
                // 今天：底层 = 状态色填充，顶层 = 紫色描边（描边不染状态色）
                val fill = ContextCompat.getDrawable(ctx, R.drawable.bg_status_pill)?.mutate()
                    ?: return
                val stroke = ContextCompat.getDrawable(ctx, R.drawable.bg_status_pill_today)?.mutate()
                    ?: return
                fill.setTint(ctx.getColor(bg))
                holder.binding.tvDay.background = LayerDrawable(arrayOf(fill, stroke))
            } else {
                holder.binding.tvDay.setBackgroundResource(R.drawable.bg_status_pill)
                holder.binding.tvDay.background.setTint(ctx.getColor(bg))
            }
        }

        private fun dayStyle(status: String): kotlin.Pair<Int, Int> = when (status) {
            "success" -> R.color.md_tertiaryContainer to R.color.md_tertiary
            "timeout" -> R.color.md_warningContainer to R.color.md_warning
            "pending", "scheduled" -> R.color.md_primaryContainer to R.color.md_primary
            "missed" -> R.color.md_missedContainer to R.color.md_missed
            "rest" -> R.color.md_tealContainer to R.color.md_teal
            "skip" -> R.color.md_skipContainer to R.color.md_skip
            else -> R.color.md_surfaceVariant to R.color.md_onSurfaceVariant
        }

        private fun dayGlyph(status: String): String = when (status) {
            "success" -> "✓"
            "timeout" -> "⚠"
            "pending", "scheduled" -> "❖"
            "missed" -> "×"
            "rest" -> "休"
            "skip" -> "跳"
            else -> "—"
        }

        /** 格子点击说明用的状态中文，与 [dayGlyph] 一一对应（口径同 DeviceControlActivity 的日历统计） */
        private fun dayDesc(status: String): String = when (status) {
            "success" -> "成功打卡"
            "timeout" -> "超时（任务已执行，结果确认偏晚，仍计入已打卡）"
            "pending" -> "待执行"
            "scheduled" -> "已计划"
            "missed" -> "未打卡"
            "rest" -> "休息日"
            "skip" -> "主动跳过"
            else -> "无数据"
        }

        override fun getItemCount() = items.size
    }
}
