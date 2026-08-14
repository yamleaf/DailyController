package com.yample.daily.controller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import com.yample.daily.controller.widget.SwipeRevealLayout

class DeviceAdapter(
    devices: List<DeviceRecord>,
    private val onClick: (DeviceRecord) -> Unit,
    private val onRename: (DeviceRecord) -> Unit,
    private val onPin: (DeviceRecord) -> Unit,
    private val onDelete: (DeviceRecord) -> Unit,
    private val onMove: (DeviceRecord, Int) -> Unit
) : ListAdapter<DeviceRecord, DeviceAdapter.ViewHolder>(DIFF_CALLBACK) {

    /** 在线状态：true=在线 / false=离线 / null=未知（3s 内未收到 retained 状态） */
    private val onlineStates = mutableMapOf<String, Boolean?>()

    /** 当前展开操作面板的 item 位置（其余 item 收起），-1=全部收起 */
    private var openPosition = RecyclerView.NO_POSITION

    /** 由 MainActivity 注入：position → SwipeRevealLayout 的查找器（避免 Adapter 持有 View 引用） */
    var openLayoutForPosition: ((Int) -> SwipeRevealLayout?)? = null

    class ViewHolder(val binding: com.yample.daily.controller.databinding.ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    init {
        // 防御性拷贝：避免外部后续修改同一 List 实例影响适配器内部状态
        submitList(ArrayList(devices))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.yample.daily.controller.databinding.ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        val binding = holder.binding
        binding.tvDeviceName.text = device.name
        binding.tvDeviceId.text = "ID: ${device.deviceId}"
        // 置顶角标：图标框右上角小图钉
        binding.imgPinBadge.visibility = if (device.pinned) View.VISIBLE else View.GONE
        val paired = device.sessionSecret.isNotBlank() && device.bound
        // 状态药丸：绿=在线已配对 / 琥珀=未配对(待配对) / 红=离线 / 灰=未知
        val online = onlineStates[device.deviceId]
        val (pillText, pillBg) = when {
            !paired -> "待配对" to R.drawable.bg_status_pill_pairing
            online == true -> "在线" to R.drawable.bg_status_pill_online
            online == false -> "离线" to R.drawable.bg_status_pill_offline
            else -> "未知" to R.drawable.bg_status_pill_offline
        }
        binding.tvStatusPill.text = pillText
        binding.tvStatusPill.setBackgroundResource(pillBg)
        // 副信息行：配对提示（后续可替换为「最后在线时间」）
        binding.tvLastSeen.text = if (paired) "已配对 · 点按进入控制" else "未配对 · 请先扫码绑定"
        // 整卡点击进入设备控制（左滑展开时点击卡片先收起再放行）
        binding.card.setOnClickListener {
            if (binding.swipeRevealLayout.isOpen) {
                closeAll()
            } else {
                onClick(device)
            }
        }
        // 重命名：长按卡片 + 左滑面板「编辑」两种入口
        binding.card.setOnLongClickListener {
            closeAll()
            onRename(device)
            true
        }
        // 左滑操作面板（QQ 式色块）：上移 / 下移 / 置顶 / 编辑（重命名）/ 删除
        // 上移下移仅在对应方向有相邻设备时可用（disabled 置灰），避免空转
        binding.btnActionMoveUp.isEnabled = position > 0
        binding.btnActionMoveUp.alpha = if (position > 0) 1f else 0.4f
        binding.btnActionMoveDown.isEnabled = position < itemCount - 1
        binding.btnActionMoveDown.alpha = if (position < itemCount - 1) 1f else 0.4f
        binding.btnActionMoveUp.setOnClickListener {
            closeAll()
            onMove(device, -1)
        }
        binding.btnActionMoveDown.setOnClickListener {
            closeAll()
            onMove(device, 1)
        }
        binding.btnActionPin.text = if (device.pinned) "取消置顶" else "置顶"
        binding.btnActionPin.setOnClickListener {
            closeAll()
            onPin(device)
        }
        binding.btnActionEdit.setOnClickListener {
            closeAll()
            onRename(device)
        }
        binding.btnActionDelete.setOnClickListener {
            closeAll()
            onDelete(device)
        }
        // 展开互斥：本 item 展开时收起其他已展开的 item
        binding.swipeRevealLayout.onStateChange = { open ->
            if (open) {
                val prev = openPosition
                if (prev != position) {
                    if (prev != RecyclerView.NO_POSITION) {
                        openLayoutForPosition?.invoke(prev)?.close()
                    }
                    openPosition = position
                }
            } else {
                if (openPosition == position) openPosition = RecyclerView.NO_POSITION
            }
        }
        // 复用复位：非展开态恢复卡片位置（不触发动画避免闪烁）
        binding.swipeRevealLayout.reset()
    }

    /** 供外部收起所有已展开的操作面板 */
    fun closeAll() {
        if (openPosition == RecyclerView.NO_POSITION) return
        val pos = openPosition
        openPosition = RecyclerView.NO_POSITION
        openLayoutForPosition?.invoke(pos)?.close()
    }

    /** A1：回填某设备的在线状态并刷新对应卡片（主线程调用） */
    fun setOnline(deviceId: String, online: Boolean?) {
        onlineStates[deviceId] = online
        val pos = currentList.indexOfFirst { it.deviceId == deviceId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DeviceRecord>() {
            override fun areItemsTheSame(old: DeviceRecord, new: DeviceRecord): Boolean {
                // 以设备 deviceId 作为稳定标识，决定 item 是否同一对象（决定复用/动画）
                return old.deviceId == new.deviceId
            }

            override fun areContentsTheSame(old: DeviceRecord, new: DeviceRecord): Boolean {
                // 界面展示受 name / 配对状态(sessionSecret+bound) / 置顶状态(pinned) 影响；在线色由适配器 onlineStates 驱动，不在此比较
                return old.name == new.name
                    && old.sessionSecret == new.sessionSecret
                    && old.bound == new.bound
                    && old.pinned == new.pinned
                    && old.group == new.group
            }
        }
    }
}
