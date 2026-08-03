package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemDeviceBinding

class DeviceAdapter(private val devices: List<DeviceRecord>, private val onClick: (DeviceRecord) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    /** 在线状态：true=在线 / false=离线 / null=未知（3s 内未收到 retained 状态） */
    private val onlineStates = mutableMapOf<String, Boolean?>()

    class ViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.binding.tvDeviceName.text = device.name
        holder.binding.tvDeviceId.text = "设备ID：${device.deviceId}"
        val paired = device.sessionSecret.isNotBlank() && device.bound
        val ctx = holder.binding.root.context
        // 状态色灯：复用设备详情页配色（与 OverviewFragment.resolveStatus 同源）
        // 绿=在线已配对 / 琥珀=未配对(待配对) / 红=已配对但离线 / 灰=未知
        val online = onlineStates[device.deviceId]
        val dotTint = when {
            !paired -> android.graphics.Color.parseColor("#F59E0B")   // 琥珀：未配对
            online == true -> android.graphics.Color.parseColor("#16A34A") // 绿：在线已配对
            online == false -> ctx.getColor(com.yample.daily.controller.R.color.md_error) // 红：离线
            else -> ctx.getColor(com.yample.daily.controller.R.color.md_outline) // 灰：未知
        }
        holder.binding.dotOnline.setBackgroundResource(com.yample.daily.controller.R.drawable.bg_dot_offline)
        holder.binding.dotOnline.background.setTint(dotTint)
        holder.binding.root.setOnClickListener { onClick(device) }
    }

    /** A1：回填某设备的在线状态并刷新对应卡片（主线程调用） */
    fun setOnline(deviceId: String, online: Boolean?) {
        onlineStates[deviceId] = online
        val pos = devices.indexOfFirst { it.deviceId == deviceId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun getItemCount() = devices.size
}
