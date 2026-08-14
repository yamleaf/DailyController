package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemBackendBinding

class BackendAdapter(
    private val items: List<ServerlessBackend>,
    private val onClick: (ServerlessBackend) -> Unit,
    private val onLongClick: (ServerlessBackend) -> Unit
) : RecyclerView.Adapter<BackendAdapter.VH>() {

    class VH(val binding: ItemBackendBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBackendBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvBackendName.text = item.name
        holder.binding.tvBackendUrl.text = item.baseUrl
        holder.binding.tvBackendMeta.text = "AppID: ${item.appId}"
        holder.binding.tvBackendMeta.visibility = View.VISIBLE
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
