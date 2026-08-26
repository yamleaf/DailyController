package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.yample.daily.controller.databinding.DialogTaskBinding
import com.yample.daily.controller.databinding.FragmentTasksBinding
import com.yample.mqttprotocol.dialog.UnifiedDialogKit

class TasksFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val taskItems = mutableListOf<TaskItem>()
    private lateinit var taskAdapter: TaskRowAdapter

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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        taskAdapter = TaskRowAdapter(taskItems,
            onEdit = { item -> if (commandsEnabled) showEditTaskPicker(item) },
            onDelete = { item ->
                if (!commandsEnabled) return@TaskRowAdapter
                showDestructiveConfirm(
                    requireContext(),
                    title = "删除任务",
                    message = "确定删除打卡时间点 ${item.time}？",
                    confirmText = "删除"
                ) { onDeleteTask?.invoke(item) }
            })
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = taskAdapter
        binding.btnAddTask.setOnClickListener { showAddTaskPicker() }
        // 视图可能晚于禁用指令创建（懒加载 tab），创建后立即应用置灰态
        binding.btnAddTask.isEnabled = commandsEnabled
        binding.btnAddTask.alpha = if (commandsEnabled) 1f else 0.45f
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        binding.tvTaskCount.text = "${s.tasks.size} 个打卡时间点"
        taskItems.clear()
        taskItems.addAll(s.tasks)
        taskAdapter.notifyDataSetChanged()
        binding.tvTasksEmpty.visibility = if (taskItems.isEmpty()) View.VISIBLE else View.GONE
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
