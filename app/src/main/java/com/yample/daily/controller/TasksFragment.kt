package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.yample.daily.controller.databinding.FragmentTasksBinding

class TasksFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val taskItems = mutableListOf<TaskItem>()
    private lateinit var taskAdapter: TaskRowAdapter

    var onAddTask: ((String) -> Unit)? = null
    var onEditTask: ((TaskItem, String) -> Unit)? = null
    var onDeleteTask: ((TaskItem) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        taskAdapter = TaskRowAdapter(taskItems,
            onEdit = { item -> showEditTaskPicker(item) },
            onDelete = { item ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除任务")
                    .setMessage("确定删除打卡时间点 ${item.time}？")
                    .setPositiveButton("删除") { _, _ -> onDeleteTask?.invoke(item) }
                    .setNegativeButton("取消", null).show()
            })
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = taskAdapter
        binding.btnAddTask.setOnClickListener { showAddTaskPicker() }
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
        showTimePicker(-1, "添加打卡时间") { time -> onAddTask?.invoke(time) }
    }

    private fun showEditTaskPicker(item: TaskItem) {
        // 预填当前任务时间（HH:mm:ss → hour/minute）
        val parts = item.time.split(":")
        val initHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val initMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        showTimePicker(initHour * 60 + initMinute, "修改打卡时间（原 ${item.time}）") { newTime ->
            onEditTask?.invoke(item, newTime)
        }
    }

    /**
     * @param presetMinutes 预选时间（分钟数），<0 表示不预填（用默认 9:00）
     * @param onPick 回调返回格式化后的 HH:mm:ss 字符串
     */
    private fun showTimePicker(presetMinutes: Int, title: String, onPick: (String) -> Unit) {
        val builder = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setTitleText(title)
        if (presetMinutes >= 0) {
            builder.setHour(presetMinutes / 60).setMinute(presetMinutes % 60)
        } else {
            builder.setHour(9).setMinute(0)
        }
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener {
            val time = String.format("%02d:%02d:00", picker.hour, picker.minute)
            onPick(time)
        }
        picker.show(parentFragmentManager, "task_time")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
