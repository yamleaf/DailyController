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
        calendarAdapter = CalendarAdapter(days, todayStr)
        binding.rvCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        binding.rvCalendar.adapter = calendarAdapter
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
            binding.layoutHistory.addView(row.root)
        }
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

    private class CalendarAdapter(private val items: List<CalendarDay>, private val todayStr: String) :
        RecyclerView.Adapter<CalendarAdapter.VH>() {
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
                return
            }
            val isToday = item.date == todayStr
            val (bg, fg) = dayStyle(item.status)
            val glyph = dayGlyph(item.status)
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

        override fun getItemCount() = items.size
    }
}
