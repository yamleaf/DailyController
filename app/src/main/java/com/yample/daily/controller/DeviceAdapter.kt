package com.yample.daily.controller

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemDeviceBinding
import com.yample.daily.controller.widget.SwipeRevealLayout

/**
 * 设备列表适配器。
 * 用可变 ArrayList 承载数据以支持长按拖拽就地重排（主界面每次重建适配器，ListAdapter 的 Diff 无从生效；
 * 且 ItemTouchHelper 拖拽需要可变列表 + notifyItemMoved 就地更新）。
 */
class DeviceAdapter(
    devices: List<DeviceRecord>,
    private val onClick: (DeviceRecord) -> Unit,
    private val onRename: (DeviceRecord) -> Unit,
    private val onPin: (DeviceRecord) -> Unit,
    private val onDelete: (DeviceRecord) -> Unit,
    private val onGroup: (DeviceRecord) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    /** 设备列表：与界面顺序一致，拖拽时就地移动 */
    private val items = ArrayList<DeviceRecord>()

    /** 在线状态：true=在线 / false=离线 / null=未知（3s 内未收到 retained 状态） */
    private val onlineStates = mutableMapOf<String, Boolean?>()

    /** 当前展开操作面板的 item 位置（其余 item 收起），-1=全部收起 */
    private var openPosition = RecyclerView.NO_POSITION

    /** 由 MainActivity 注入：position → SwipeRevealLayout 的查找器（避免 Adapter 持有 View 引用） */
    var openLayoutForPosition: ((Int) -> SwipeRevealLayout?)? = null

    /** 由 MainActivity 注入：长按卡片时调用 startDrag 启动拖拽的 ItemTouchHelper */
    var itemTouchHelper: ItemTouchHelper? = null

    class ViewHolder(val binding: com.yample.daily.controller.databinding.ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    init {
        items.addAll(devices)
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): DeviceRecord = items[position]

    /** 拖拽结束时供外部读取当前顺序（落定写库用） */
    fun currentOrder(): List<DeviceRecord> = items.toList()

    /**
     * 拖拽过程中把 [from] 位置的项移到 [to]（就地更新 + notifyItemMoved 驱动换位动画）。
     * 置顶区与非置顶区交界处不允许拖拽跨越（置顶始终置前）。
     */
    fun onMoveItems(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return false
        if (items[from].pinned != items[to].pinned) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
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
        val bound = device.sessionSecret.isNotBlank() && device.bound
        val pairing = device.pairingToken.isNotBlank() && !bound
        val online = onlineStates[device.deviceId]
        // 状态药丸：配对状态 × 在线状态 分开展示，避免设备解绑后仍显示绿色「在线」
        // 绿=在线·绑定 / 橙=在线·解绑(可达但未绑定，需重新配对) / 灰=离线·解绑 / 琥珀=等待(探活中或配对中)
        val (pillText, pillBg) = when {
            pairing -> "等待配对" to R.drawable.bg_status_pill_pairing
            !bound -> when (online) {
                true -> "在线·解绑" to R.drawable.bg_status_pill_warning
                false -> "离线·解绑" to R.drawable.bg_status_pill_offline
                else -> "解绑" to R.drawable.bg_status_pill_offline
            }
            else -> when (online) {
                true -> "在线·绑定" to R.drawable.bg_status_pill_online
                false -> "离线·绑定" to R.drawable.bg_status_pill_offline
                else -> "未知等待" to R.drawable.bg_status_pill_pairing
            }
        }
        binding.tvStatusPill.text = pillText
        binding.tvStatusPill.setBackgroundResource(pillBg)
        // 副信息行：配对提示（后续可替换为「最后在线时间」）
        binding.tvLastSeen.text = when {
            pairing -> "等待配对 · 请保持被控端在线"
            bound -> "已绑定 · 点按进入控制"
            else -> "已解绑 · 请重新扫码绑定"
        }
        // 整卡点击进入设备控制（左滑展开时点击卡片先收起再放行）
        binding.card.setOnClickListener {
            if (binding.swipeRevealLayout.isOpen) {
                closeAll()
            } else {
                onClick(device)
            }
        }
        // 长按整卡：启动拖拽排序（重命名入口让位，重命名仅保留面板「编辑」按钮）
        binding.card.setOnLongClickListener {
            closeAll()
            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            itemTouchHelper?.startDrag(holder)
            true
        }
        // 左滑操作面板（QQ 式色块）：分组 / 置顶 / 编辑（重命名）/ 删除
        binding.btnActionGroup.setOnClickListener {
            closeAll()
            onGroup(device)
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
        // 四卡阶梯式层叠左滑（与任务卡片同一套效果；进度回调驱动，不设则走原行为）
        val density = holder.itemView.resources.displayMetrics.density
        binding.swipeRevealLayout.onScrollProgress = { p -> applyStackedActions(binding, p, density) }

        // 展开互斥：本 item 展开时收起其他已展开的 item
        // 回调里用 holder.bindingAdapterPosition 取实时位置（列表变更后 onBindViewHolder 形参 position 可能过期）
        binding.swipeRevealLayout.onStateChange = { open ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                if (open) {
                    val prev = openPosition
                    if (prev != pos) {
                        if (prev != RecyclerView.NO_POSITION) {
                            openLayoutForPosition?.invoke(prev)?.close()
                        }
                        openPosition = pos
                    }
                } else {
                    if (openPosition == pos) openPosition = RecyclerView.NO_POSITION
                }
            }
        }
        // 复用复位：非展开态恢复卡片位置（不触发动画避免闪烁）
        binding.swipeRevealLayout.reset()
    }

    /**
     * 四卡阶梯式层叠左滑（与任务卡片同一套效果，纯 View 属性动画，R8 下零风险）：
     * 内容卡片轻微下沉 + 缩小；删除→编辑→分组→置顶依次抽出，
     * 各卡水平错开 40dp、垂直递减、旋转角递增，像一叠斜放的卡片。
     */
    private fun applyStackedActions(b: ItemDeviceBinding, p: Float, density: Float) {
        // 内容卡片：下沉 4dp、缩小到 0.985、阴影 0→2dp（幅度克制）
        b.card.translationY = STACK_CARD_DROP * density * p
        val cardScale = 1f - STACK_CARD_SHRINK * p
        b.card.scaleX = cardScale
        b.card.scaleY = cardScale
        b.card.cardElevation = STACK_CARD_ELEV * density * p

        // 四卡依次抽出，每卡占 0.25 进度窗口
        applyCard(b.btnActionDelete, cardT(p, 0), density, x = 0f, y = 9f, rot = 5f)
        applyCard(b.btnActionEdit, cardT(p, 1), density, x = -40f, y = 3f, rot = 2f)
        applyCard(b.btnActionGroup, cardT(p, 2), density, x = -80f, y = -3f, rot = -2f)
        applyCard(b.btnActionPin, cardT(p, 3), density, x = -120f, y = -9f, rot = -5f)
    }

    /** 第 index 张卡的抽出进度：每张错开 0.18 进度、持续 0.46 */
    private fun cardT(p: Float, index: Int): Float {
        val start = index * 0.18f
        val t = ((p - start) / 0.46f).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t) // easeOut
    }

    /** 单卡从重合态抽出到错位态（alpha / scale / 位移 / 旋转） */
    private fun applyCard(view: View, t: Float, density: Float, x: Float, y: Float, rot: Float) {
        view.alpha = t
        val scale = 0.6f + 0.4f * t
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = x * density * t
        view.translationY = y * density * t
        view.rotation = rot * t
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
        val pos = items.indexOfFirst { it.deviceId == deviceId }
        if (pos >= 0) notifyItemChanged(pos)
    }

    companion object {
        // 内容卡片层叠幅度（单位 dp，运行时乘 density）
        private const val STACK_CARD_DROP = 4f
        private const val STACK_CARD_SHRINK = 0.015f
        private const val STACK_CARD_ELEV = 2f
    }
}