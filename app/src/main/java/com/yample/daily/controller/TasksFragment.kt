package com.yample.daily.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.yample.daily.controller.databinding.DialogTaskBinding
import com.yample.daily.controller.databinding.FragmentTasksBinding
import com.yample.daily.controller.databinding.SheetTaskActionsBinding
import com.yample.daily.controller.widget.SwipeRevealLayout
import com.yample.daily.controller.widget.TaskChainDecoration
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import java.util.Locale

class TasksFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val taskItems = mutableListOf<TaskItem>()
    private lateinit var taskAdapter: TaskRowAdapter

    /** 链式彗星动画装饰器；需在视图销毁时 release，否则无限动画会一直跑并持有 RecyclerView */
    private var chainDecoration: TaskChainDecoration? = null

    var onAddTask: ((String, String) -> Unit)? = null
    var onEditTask: ((TaskItem, String, String) -> Unit)? = null
    var onDeleteTask: ((TaskItem) -> Unit)? = null

    private var commandsEnabled = true

    /** 解绑态禁用任务增删改（按钮置灰，编辑/删除弹窗不再弹出） */
    fun setCommandsEnabled(enabled: Boolean) {
        commandsEnabled = enabled
        if (_binding == null) return
        binding.btnAddTask.isEnabled = enabled
        binding.btnAddTask.alpha = if (enabled) 1f else 0.45f
        if (::taskAdapter.isInitialized) taskAdapter.setCommandsEnabled(enabled)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 提前取 context：弹层显示期间 Fragment 若 detach，点击回调里再 requireContext() 会抛异常
        val ctx = requireContext()
        taskAdapter = TaskRowAdapter(taskItems,
            onEdit = { item -> if (commandsEnabled) showEditTaskPicker(item) },
            onDelete = { item ->
                if (commandsEnabled) confirmDeleteTask(ctx, item)
            },
            onLongMenu = { item -> if (commandsEnabled) showTaskActions(ctx, item) })
        taskAdapter.openLayoutForPosition = { pos ->
            _binding?.rvTasks?.findViewHolderForAdapterPosition(pos)?.itemView as? SwipeRevealLayout
        }
        binding.rvTasks.layoutManager = LinearLayoutManager(ctx)
        binding.rvTasks.adapter = taskAdapter
        // 链式时间轴：卡片间灰线 + 卡片左内侧流动彗星光点（已适配卡片化布局）
        chainDecoration = TaskChainDecoration(ctx).also { binding.rvTasks.addItemDecoration(it) }
        binding.btnAddTask.setOnClickListener { showAddTaskPicker() }
        // 视图可能晚于禁用指令创建（懒加载 tab），创建后立即应用置灰态
        binding.btnAddTask.isEnabled = commandsEnabled
        binding.btnAddTask.alpha = if (commandsEnabled) 1f else 0.45f
        taskAdapter.setCommandsEnabled(commandsEnabled)
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        // 快照可能早于 onViewCreated 到达，此时 adapter 尚未初始化；snapshot 已留存，视图创建后会补渲染
        if (!::taskAdapter.isInitialized) return
        binding.tvTaskCount.text = "${s.tasks.size} 个打卡时间点"
        // 数据整体替换前先收起展开态，避免 openPosition 指向旧位置
        taskAdapter.closeAll()
        val randomEnabled = settingBool(s, "rt", true)
        val minuteRange = settingInt(s, "tr", DEFAULT_TIME_RANGE)
        taskItems.clear()
        taskItems.addAll(s.tasks.map {
            it.copy(randomWindow = resolveRandomWindow(it.time, randomEnabled, minuteRange))
        })
        taskAdapter.notifyDataSetChanged()
        binding.tvTasksEmpty.visibility = if (taskItems.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 随机时间窗口展示值，与被控端 DailyTaskBean.resolveRandomTimeRange 完全对齐：
     * 最晚执行 = 起始 + (minuteRange-1)*60 + 59 秒，再被当天 23:59:59 截断。
     *
     * 数据来源为快照 settings 的 rt（随机时间开关）/ tr（随机范围分钟），与被控端列表同一份配置，
     * 因此无需被控端下发新字段、无需改 protocol 子模块，也兼容旧版本被控端。
     * 关闭随机或时间格式异常时返回 null，卡片显示「未启用」/ 占位。
     */
    private fun resolveRandomWindow(time: String, enabled: Boolean, minuteRange: Int): String? {
        if (!enabled) return null
        val parts = time.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: return null
        val sec = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val start = h * 3600 + m * 60 + sec
        val range = minuteRange.coerceIn(0, 60)
        val end = if (range > 0) minOf(start + (range - 1) * 60 + 59, MAX_SECONDS_OF_DAY) else start
        fun fmt(total: Int) = String.format(Locale.ROOT, "%02d:%02d", total / 3600, (total % 3600) / 60)
        return "${fmt(start)} - ${fmt(end)}"
    }

    /** settings 字段可能被远程改值写入不同类型，宽松解析避免 ClassCastException */
    private fun settingBool(s: DeviceSnapshot, key: String, fallback: Boolean): Boolean =
        when (val v = s.settings.firstOrNull { it.key == key }?.value) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            else -> fallback
        }

    private fun settingInt(s: DeviceSnapshot, key: String, fallback: Int): Int =
        when (val v = s.settings.firstOrNull { it.key == key }?.value) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: fallback
            else -> fallback
        }

    /**
     * 任务卡片长按弹层：上半部分是「任务详情」只读区，下半部分才是操作。
     *
     * 与左滑的职责分工：左滑 = 快捷操作（明确知道要做什么，一步到位）；
     * 长按 = 先看全信息再决定操作，并额外提供「复制打卡时间」这类左滑没有的能力。
     * 用捕获的 ctx，避免弹层显示期间 Fragment detach 后 requireContext() 抛异常。
     */
    private fun showTaskActions(ctx: Context, item: TaskItem) {
        val sheetBinding = SheetTaskActionsBinding.inflate(layoutInflater)

        // 标题右侧状态胶囊：与卡片同一套配色
        sheetBinding.tvActionStatus.text = item.statusLabel
        val (textColor, bg) = TaskRowAdapter.statusStyle(item.status)
        sheetBinding.tvActionStatus.setTextColor(ContextCompat.getColor(ctx, textColor))
        sheetBinding.tvActionStatus.background?.mutate()?.setTint(ContextCompat.getColor(ctx, bg))

        // 详情只读区：卡片上被省略/压缩的信息在这里完整呈现
        sheetBinding.tvDetailTime.text = item.time
        sheetBinding.tvDetailRandomWindow.text = item.randomWindow ?: "未启用"
        sheetBinding.tvDetailActual.text = item.actualTime?.takeIf { it.isNotBlank() } ?: "--:--:--"
        sheetBinding.tvDetailName.text = item.name.takeIf { it.isNotBlank() } ?: "无备注"

        val sheet = BottomSheetDialog(ctx)
        sheet.setContentView(sheetBinding.root)

        // 复制打卡时间：左滑面板没有的能力，让长按有独立价值
        sheetBinding.btnCopyTime.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("打卡时间", item.time))
            // Android 13+ 系统自带剪贴板浮层提示，不再重复弹 Toast
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(ctx, "已复制打卡时间 ${item.time}", Toast.LENGTH_SHORT).show()
            }
        }

        sheetBinding.cardActionEdit.setOnClickListener {
            sheet.dismiss()
            showEditTaskPicker(item)
        }
        sheetBinding.cardActionDelete.setOnClickListener {
            sheet.dismiss()
            confirmDeleteTask(ctx, item)
        }
        sheetBinding.btnActionCancel.setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmDeleteTask(ctx: Context, item: TaskItem) {
        showDestructiveConfirm(
            ctx,
            title = "删除任务",
            message = "确定删除打卡时间点 ${item.time}？",
            confirmText = "删除"
        ) { onDeleteTask?.invoke(item) }
    }

    private fun showAddTaskPicker() {
        if (!commandsEnabled) return
        showTaskDialog("添加任务时间", "09:00:00", "") { time, name ->
            onAddTask?.invoke(time, name)
        }
    }

    private fun showEditTaskPicker(item: TaskItem) {
        showTaskDialog("修改打卡时间（原 ${item.time}）", item.time, item.name) { newTime, name ->
            onEditTask?.invoke(item, newTime, name)
        }
    }

    /**
     * 任务对话框：时间行可点击调起 MaterialTimePicker，另含可选名称输入框（多任务命名）。
     * @param onPick 回调返回 (HH:mm:ss, 名称)
     */
    private fun showTaskDialog(title: String, initialTime: String, initialName: String, onPick: (String, String) -> Unit) {
        val dlgBinding = DialogTaskBinding.inflate(LayoutInflater.from(requireContext()))
        dlgBinding.tvTaskTimeValue.text = initialTime

        // 时间行点击 → 时间选择器，选择后回填显示
        fun pickTime() {
            val parts = initialTime.split(":")
            val initHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
            val initMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setTitleText("选择打卡时间")
                .setHour(initHour)
                .setMinute(initMinute)
                .build()
            picker.addOnPositiveButtonClickListener {
                dlgBinding.tvTaskTimeValue.text =
                    String.format("%02d:%02d:00", picker.hour, picker.minute)
            }
            picker.show(parentFragmentManager, "task_time")
        }
        dlgBinding.btnPickTime.setOnClickListener { pickTime() }
        dlgBinding.etTaskName.setText(initialName)

        UnifiedDialogKit.showForm(
            ctx = requireContext(),
            contentView = dlgBinding.root,
            title = title,
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val time = dlgBinding.tvTaskTimeValue.text.toString()
                val name = dlgBinding.etTaskName.text?.toString()?.trim().orEmpty()
                onPick(time, name)
                true
            }
        )
    }

    /**
     * tab 切换（hide/show）时暂停/恢复链式脉冲动画。
     *
     * hide 后 RecyclerView 已是 GONE、不再重绘，但无限 ValueAnimator 仍会每帧
     * 调 postInvalidateOnAnimation，属于纯空转；这里显式暂停，切回时再恢复。
     * 首次显示（尚未创建动画）时 resume 是空操作，动画由 onDraw 自行 start。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) chainDecoration?.pause() else chainDecoration?.resume()
    }

    override fun onDestroyView() {
        // 必须 release：装饰器里的循环动画否则会一直跑，并通过 host 持有 RecyclerView / 整套视图
        chainDecoration?.release()
        chainDecoration = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 与被控端 Constant.DEFAULT_TIME_RANGE 一致；快照缺 settings 时兜底 */
        private const val DEFAULT_TIME_RANGE = 5
        private const val MAX_SECONDS_OF_DAY = 86_399
    }
}
