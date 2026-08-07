package com.yample.daily.controller

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yample.daily.controller.R

/**
 * 双端统一弹窗工具类（控制端 DailyController）。
 * 与被控端保持同一套 4 种标准弹窗（成功 / 警告 / 权限 / 信息），
 * 统一 16dp 圆角、胶囊按钮、缩放动画与 56dp 圆形图标容器。
 *
 * 按钮规则（统一规范）：
 *  - 两个按钮：底部等宽均分（各 weight=1）
 *  - 单个按钮：底部居中、自然宽度
 *  - 标题/正文：始终居中
 *  - 警告类：主按钮染危险色（md_error 实心）
 */
object UnifiedDialogKit {

    enum class IconType { SUCCESS, WARNING, PERMISSION, INFO }

    private data class IconSpec(
        val drawable: Int,
        val containerTint: Int,
        val iconTint: Int
    )

    private fun specFor(type: IconType): IconSpec = when (type) {
        IconType.SUCCESS -> IconSpec(
            R.drawable.ic_dialog_check,
            R.color.md_tertiaryContainer,
            R.color.md_tertiary
        )
        IconType.WARNING -> IconSpec(
            R.drawable.ic_dialog_warning,
            R.color.md_errorContainer,
            R.color.md_error
        )
        IconType.PERMISSION -> IconSpec(
            R.drawable.ic_dialog_permission,
            R.color.md_primaryContainer,
            R.color.md_primary
        )
        IconType.INFO -> IconSpec(
            R.drawable.ic_dialog_info,
            R.color.md_primaryContainer,
            R.color.md_primary
        )
    }

    private fun buildContent(
        ctx: Context,
        type: IconType,
        title: String,
        message: String
    ): View {
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_unified_content, null)
        val spec = specFor(type)
        val icon = view.findViewById<ImageView>(R.id.ivDialogIcon)
        icon.backgroundTintList = ContextCompat.getColorStateList(ctx, spec.containerTint)
        icon.setImageResource(spec.drawable)
        icon.imageTintList = ContextCompat.getColorStateList(ctx, spec.iconTint)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        view.findViewById<TextView>(R.id.tvDialogMessage).text = message
        return view
    }

    /**
     * 装配底部按钮栏。
     * @param negativeText 为 null 表示仅单按钮（居中自然宽度）；否则双按钮等宽均分。
     * @param danger 主按钮是否染危险色（md_error 实心）。
     */
    private fun configureButtons(
        dlg: AlertDialog,
        ctx: Context,
        content: View,
        positiveText: String,
        negativeText: String?,
        danger: Boolean,
        onPositive: (() -> Unit)?,
        onNegative: (() -> Unit)?
    ) {
        val btnBar = content.findViewById<LinearLayout>(R.id.btnBar)
        val btnPos = content.findViewById<Button>(R.id.btnPositive)
        val btnNeg = content.findViewById<Button>(R.id.btnNegative)

        btnPos.text = positiveText
        btnPos.visibility = View.VISIBLE
        btnPos.setOnClickListener {
            onPositive?.invoke()
            dlg.dismiss()
        }
        if (danger) {
            btnPos.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.md_error)
            btnPos.setTextColor(ContextCompat.getColor(ctx, R.color.md_onError))
        }

        if (negativeText != null) {
            // 双按钮：等宽均分
            btnNeg.text = negativeText
            btnNeg.visibility = View.VISIBLE
            btnNeg.setOnClickListener {
                onNegative?.invoke()
                dlg.dismiss()
            }
            btnBar.gravity = Gravity.CENTER
        } else {
            // 单按钮：居中、自然宽度
            val lp = btnPos.layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            lp.marginStart = 0
            btnPos.layoutParams = lp
            btnBar.gravity = Gravity.CENTER
        }
    }

    private fun createDialog(
        ctx: Context,
        type: IconType,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String?,
        danger: Boolean,
        cancelable: Boolean,
        onPositive: (() -> Unit)?,
        onNegative: (() -> Unit)?
    ): AlertDialog {
        val content = buildContent(ctx, type, title, message)
        val dlg = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DC_UnifiedDialog)
            .setView(content)
            .create()
        dlg.setCancelable(cancelable)
        configureButtons(dlg, ctx, content, positiveText, negativeText, danger, onPositive, onNegative)
        return dlg.apply { show() }
    }

    /** ① 成功 / 提示弹窗（双按钮：次要 + 主） */
    fun showSuccess(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = ctx.getString(android.R.string.ok),
        cancelText: String = ctx.getString(android.R.string.cancel),
        cancelable: Boolean = true,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ): AlertDialog = createDialog(
        ctx, IconType.SUCCESS, title, message, confirmText, cancelText,
        danger = false, cancelable = cancelable, onPositive = onConfirm, onNegative = onCancel
    )

    /** ② 警告 / 删除确认弹窗（主按钮染危险色） */
    fun showWarning(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "删除",
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ): AlertDialog = createDialog(
        ctx, IconType.WARNING, title, message, confirmText, ctx.getString(android.R.string.cancel),
        danger = true, cancelable = cancelable, onPositive = onConfirm, onNegative = onCancel
    )

    /** ③ 权限申请弹窗（双按钮：拒绝 + 允许） */
    fun showPermission(
        ctx: Context,
        title: String,
        message: String,
        grantText: String = "允许",
        denyText: String = "拒绝",
        cancelable: Boolean = true,
        onGrant: () -> Unit
    ): AlertDialog = createDialog(
        ctx, IconType.PERMISSION, title, message, grantText, denyText,
        danger = false, cancelable = cancelable, onPositive = onGrant, onNegative = null
    )

    /** ⑤ 信息提示弹窗（单按钮居中） */
    fun showInfo(
        ctx: Context,
        title: String,
        message: String,
        buttonText: String = "知道了",
        cancelable: Boolean = true
    ): AlertDialog = createDialog(
        ctx, IconType.INFO, title, message, buttonText, null,
        danger = false, cancelable = cancelable, onPositive = null, onNegative = null
    )

    /**
     * ④ 表单 / 自定义弹窗（统一按钮规则：双按钮等宽均分，单按钮居中）。
     * @param contentView 自定义内容（EditText / 表单布局 / 滚动视图等），注入 contentHost。
     * @param title 可选标题，居中；null/空则隐藏。
     * @param message 可选辅助说明，左对齐；null/空则隐藏。
     * @param negativeText 为 null 表示仅单按钮（居中自然宽度）；否则双按钮等宽均分。
     * @param onShow 弹窗显示后回传 dialog 与两个按钮，供实时校验/动态禁用。
     * @param onConfirm 点击主按钮；返回 false 表示不关闭弹窗（校验失败），true/null 关闭。
     * @param onCancel 点击次按钮；返回 false 不关闭，其它关闭。
     */
    fun showForm(
        ctx: Context,
        contentView: View,
        title: String? = null,
        message: String? = null,
        positiveText: String = "确定",
        negativeText: String? = "取消",
        cancelable: Boolean = true,
        onShow: ((dialog: AlertDialog, positive: Button, negative: Button) -> Unit)? = null,
        onConfirm: ((dialog: AlertDialog) -> Boolean)? = null,
        onCancel: ((dialog: AlertDialog) -> Boolean)? = null
    ): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_unified_form, null)
        val titleView = view.findViewById<TextView>(R.id.tvDialogTitle)
        val titleGap = view.findViewById<View>(R.id.titleGap)
        if (!title.isNullOrBlank()) {
            titleView.text = title
            titleView.visibility = View.VISIBLE
            titleGap.visibility = View.VISIBLE
        }
        val msgView = view.findViewById<TextView>(R.id.tvDialogMessage)
        val msgGap = view.findViewById<View>(R.id.messageGap)
        if (!message.isNullOrBlank()) {
            msgView.text = message
            msgView.visibility = View.VISIBLE
            msgGap.visibility = View.VISIBLE
        }
        val host = view.findViewById<FrameLayout>(R.id.contentHost)
        host.addView(contentView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dlg = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DC_UnifiedDialog)
            .setView(view)
            .create()

        val btnBar = view.findViewById<LinearLayout>(R.id.btnBar)
        val btnPos = view.findViewById<Button>(R.id.btnPositive)
        val btnNeg = view.findViewById<Button>(R.id.btnNegative)

        btnPos.text = positiveText
        btnPos.visibility = View.VISIBLE
        btnPos.setOnClickListener {
            if (onConfirm?.invoke(dlg) != false) dlg.dismiss()
        }

        if (negativeText != null) {
            // 双按钮：等宽均分
            btnNeg.text = negativeText
            btnNeg.visibility = View.VISIBLE
            btnNeg.setOnClickListener {
                if (onCancel?.invoke(dlg) != false) dlg.dismiss()
            }
            btnBar.gravity = Gravity.CENTER
        } else {
            // 单按钮：居中、自然宽度
            val lp = btnPos.layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            btnPos.layoutParams = lp
            btnBar.gravity = Gravity.CENTER
        }

        dlg.setCancelable(cancelable)
        dlg.setOnShowListener { onShow?.invoke(dlg, btnPos, btnNeg) }
        dlg.show()
        return dlg
    }

    /** ④ 表单 / 自定义弹窗：返回已套用统一主题的 builder，调用方自行 setView / 装载布局 */
    fun builder(ctx: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_DC_UnifiedDialog)
}
