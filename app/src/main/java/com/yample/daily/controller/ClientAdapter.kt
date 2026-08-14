package com.yample.daily.controller

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.ServerlessApiClient.ServerlessClient
import com.yample.daily.controller.databinding.ItemClientBinding

class ClientAdapter(
    private val items: List<ServerlessClient>,
    private val onClick: (ServerlessClient) -> Unit,
    private val onKick: (ServerlessClient) -> Unit
) : RecyclerView.Adapter<ClientAdapter.VH>() {

    class VH(val binding: ItemClientBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        val dot = holder.binding.dotStatus
        dot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (item.connected) Color.GREEN else Color.GRAY)
        }
        holder.binding.tvClientId.text = item.clientId
        val sub = buildString {
            if (item.username.isNotBlank()) append(item.username).append("  ")
            if (item.ip.isNotBlank()) append(item.ip).append(if (item.port.isNotBlank()) ":${item.port}" else "")
            append("  订阅${item.subscriptionsCnt}")
        }.trim()
        holder.binding.tvClientSub.text = sub
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnKick.setTextColor(ContextCompat.getColor(ctx, R.color.md_error))
        holder.binding.btnKick.setOnClickListener { onKick(item) }
    }
}
