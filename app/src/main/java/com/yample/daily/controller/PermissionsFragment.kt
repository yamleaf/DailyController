package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.yample.daily.controller.databinding.FragmentPermissionsBinding
import com.yample.daily.controller.databinding.RowInfoBinding

class PermissionsFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        binding.layoutStatuses.removeAllViews()
        s.statuses.forEach { st ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = st.label
            row.tvRowValue.text = st.value
            row.tvRowValue.setTextColor(statusColor(st.value))
            binding.layoutStatuses.addView(row.root)
        }
    }

    private fun statusColor(value: String): Int {
        return requireContext().getColor(when {
            value.contains("已获取") || value.contains("已开启") || value == "正常" || value.contains("截屏") || value.contains("通知") -> R.color.md_tertiary
            value.contains("未") || value == "已授权但断开" -> R.color.md_error
            else -> R.color.md_onSurface
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
