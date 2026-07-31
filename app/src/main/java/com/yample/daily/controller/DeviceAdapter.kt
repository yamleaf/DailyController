package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemDeviceBinding

class DeviceAdapter(private val devices: List<DeviceRecord>, private val onClick: (DeviceRecord) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.binding.tvDeviceName.text = device.name
        holder.binding.tvDeviceId.text = device.deviceId
        holder.binding.root.setOnClickListener { onClick(device) }
    }

    override fun getItemCount() = devices.size
}
