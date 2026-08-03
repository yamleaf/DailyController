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
    var onDeleteTask: ((TaskItem) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        taskAdapter = TaskRowAdapter(taskItems, onDelete = { item ->
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
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(9)
            .setMinute(0)
            .setTitleText("添加打卡时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            val time = String.format("%02d:%02d:00", picker.hour, picker.minute)
            onAddTask?.invoke(time)
        }
        picker.show(parentFragmentManager, "add_task")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
