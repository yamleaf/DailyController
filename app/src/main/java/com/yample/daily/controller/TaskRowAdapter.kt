package com.yample.daily.controller

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yample.daily.controller.databinding.ItemTaskRowBinding
import com.yample.daily.controller.widget.SwipeRevealLayout

/**
 * 任务卡片适配器：一任务一卡片，左滑露出编辑 / 删除，长按弹出统一风格菜单。
 *
 * 左滑互斥逻辑与设备列表 DeviceAdapter 保持一致（openPosition + openLayoutForPosition），
 * 保证同一时刻只有一张卡片处于展开态；复用视图时 reset() 清除残留位移。
 */
class TaskRowAdapter(
    private val items: MutableList<TaskItem>,
    private val onEdit: (TaskItem) -> Unit,
    private val onDelete: (TaskItem) -> Unit,
    private val onLongMenu: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskRowAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTaskRowBinding) : RecyclerView.ViewHolder(binding.root)

    /** 解绑态禁用任务增删改：操作按钮置灰不可点、长按菜单不弹出 */
    private var commandsEnabled = true

    /** 当前展开操作面板的 item 位置，-1=全部收起 */
    private var openPosition = RecyclerView.NO_POSITION

    /** 由 TasksFragment 注入：position → SwipeRevealLayout 的查找器（避免 Adapter 持有 View 引用） */
    var openLayoutForPosition: ((Int) -> SwipeRevealLayout?)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTaskRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = holder.itemView.context

        b.tvTaskTime.text = item.time
        // 备注：无备注时文本留空但控件保留，首行高度与三段布局保持稳定
        b.tvTaskName.text = item.name
        b.tvTaskRandomWindow.text = item.randomWindow ?: "未启用"
        b.tvTaskActual.text = item.actualTime?.takeIf { it.isNotBlank() } ?: "--:--:--"
        b.tvTaskStatus.text = item.statusLabel

        val (textColor, bg) = statusStyle(item.status)
        b.tvTaskStatus.setTextColor(ContextCompat.getColor(ctx, textColor))
        // mutate()：避免共享 Drawable 常量状态导致不同状态行的胶囊颜色互相污染
        b.tvTaskStatus.background?.mutate()?.setTint(ContextCompat.getColor(ctx, bg))

        // 解绑态：与「添加任务」按钮保持同一禁用语义（置灰 + 不可点）
        b.btnActionEdit.isEnabled = commandsEnabled
        b.btnActionDelete.isEnabled = commandsEnabled

        // 整卡点击：展开态先收起，其余情况不做任何事（避免误触编辑）
        b.card.setOnClickListener {
            if (b.swipeRevealLayout.isOpen) closeAll()
        }
        // 整卡长按：统一风格菜单「编辑 / 删除」
        b.card.setOnLongClickListener {
            val target = itemAt(holder)
            if (target != null && commandsEnabled) {
                closeAll()
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongMenu(target)
            }
            true
        }
        b.btnActionEdit.setOnClickListener {
            closeAll()
            itemAt(holder)?.let(onEdit)
        }
        b.btnActionDelete.setOnClickListener {
            closeAll()
            itemAt(holder)?.let(onDelete)
        }

        // WorkBuddy 式堆叠左滑：进度回调驱动「卡片下沉 + 按钮逐层弹出」
        // 该回调只在本适配器设置，设备列表仍走原 SwipeRevealLayout 行为，零回归。
        val density = ctx.resources.displayMetrics.density
        b.swipeRevealLayout.onScrollProgress = { p -> applyStackEffect(b, p, density) }

        // 展开互斥 + 复用复位（reset 不触发 onStateChange，故 openPosition 由 render 前 closeAll 清理）
        b.swipeRevealLayout.onStateChange = { open ->
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
                } else if (openPosition == pos) {
                    openPosition = RecyclerView.NO_POSITION
                }
            }
        }
        b.swipeRevealLayout.reset()
    }

    /**
     * 层层错位堆叠的左滑效果（纯 View 属性动画，无 animator 资源，R8 下零风险）：
     * - 内容卡片随进度下沉 + 缩小 + 阴影加深，像被"掀开"露出底层；
     * - 删除卡（底层）先抽出：落到右侧、下沉、顺时针倾斜；
     * - 编辑卡（上层）后抽出：落到左侧、上浮、逆时针倾斜；
     *   两卡由重合状态逐渐错位，最终像一叠斜放的卡片，各露一侧可点。
     *
     * 注意：alpha 会覆盖解绑态置灰 alpha，故统一乘 [actionAlpha]，
     *       置灰时按钮整体半透明且不可点（与「添加任务」一致）。
     */
    private fun applyStackEffect(b: com.yample.daily.controller.databinding.ItemTaskRowBinding, p: Float, density: Float) {
        val actionAlpha = if (commandsEnabled) 1f else 0.45f

        // 内容卡片被掀开：下沉 0→8dp、缩小 1→0.955、阴影 0→4dp
        b.card.translationY = STACK_CARD_DROP * density * p
        val cardScale = 1f - STACK_CARD_SHRINK * p
        b.card.scaleX = cardScale
        b.card.scaleY = cardScale
        b.card.cardElevation = STACK_CARD_ELEV * density * p

        // 底层删除卡 0→0.55 抽出；上层编辑卡 0.22→1 抽出（错位有先后）
        val deleteT = easeOut((p / 0.55f).coerceIn(0f, 1f))
        val editT = easeOut(((p - 0.22f) / 0.78f).coerceIn(0f, 1f))
        // 收起时两卡重合在面板右缘（translation 为 0）；展开时分别落到各自错位位置
        applyStackedCard(b.btnActionDelete, deleteT, actionAlpha, density,
            targetX = 0f, targetY = STACK_DELETE_DROP_Y, targetRot = STACK_DELETE_ROT, targetScale = 0.94f)
        applyStackedCard(b.btnActionEdit, editT, actionAlpha, density,
            targetX = -STACK_EDIT_SHIFT_X, targetY = -STACK_EDIT_RISE_Y, targetRot = -STACK_EDIT_ROT, targetScale = 1f)
    }

    /**
     * 单张卡片从"重合态"抽出到目标错位态。
     * t=0：alpha 0、scale 0.6、无位移无旋转（贴面板右缘的隐藏叠层）；
     * t=1：落到 [targetX]/[targetY]、旋转 [targetRot]、缩放 [targetScale]。
     */
    private fun applyStackedCard(
        view: View, t: Float, actionAlpha: Float, density: Float,
        targetX: Float, targetY: Float, targetRot: Float, targetScale: Float
    ) {
        view.alpha = actionAlpha * t
        val scale = 0.6f + (targetScale - 0.6f) * t
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = targetX * density * t
        view.translationY = targetY * density * t
        view.rotation = targetRot * t
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /** 用实时位置取数据，避免列表变更后闭包里捕获的旧对象被操作 */
    private fun itemAt(holder: ViewHolder): TaskItem? {
        val pos = holder.bindingAdapterPosition
        return if (pos != RecyclerView.NO_POSITION && pos < items.size) items[pos] else null
    }

    /** 解绑 / 重新绑定时刷新操作按钮可用态 */
    fun setCommandsEnabled(enabled: Boolean) {
        if (commandsEnabled == enabled) return
        commandsEnabled = enabled
        openPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    /** 供外部（含数据刷新前）收起所有已展开的操作面板 */
    fun closeAll() {
        if (openPosition == RecyclerView.NO_POSITION) return
        val pos = openPosition
        openPosition = RecyclerView.NO_POSITION
        openLayoutForPosition?.invoke(pos)?.close()
    }

    /** 状态胶囊：文字色 + 背景色（与原任务行状态配色一致） */
    private fun statusStyle(status: String): Pair<Int, Int> = when (status) {
        "success" -> R.color.md_tertiary to R.color.md_tertiaryContainer
        "timeout" -> R.color.md_warning to R.color.md_warningContainer
        "pending" -> R.color.md_primary to R.color.md_primaryContainer
        "skip" -> R.color.md_onSurfaceVariant to R.color.md_skipContainer
        "expired" -> R.color.md_onSurfaceVariant to R.color.md_surfaceVariant
        else -> R.color.md_onSurfaceVariant to R.color.md_surfaceVariant
    }

    override fun getItemCount() = items.size

    companion object {
        // 堆叠效果幅度（单位 dp，运行时乘 density）
        private const val STACK_CARD_DROP = 8f
        private const val STACK_CARD_SHRINK = 0.045f
        private const val STACK_CARD_ELEV = 4f
        // 删除卡：右侧、下沉、顺时针
        private const val STACK_DELETE_DROP_Y = 10f
        private const val STACK_DELETE_ROT = 6f
        // 编辑卡：左侧错位、上浮、逆时针
        private const val STACK_EDIT_SHIFT_X = 58f
        private const val STACK_EDIT_RISE_Y = 8f
        private const val STACK_EDIT_ROT = -5f
    }
}
