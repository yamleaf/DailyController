package com.yample.daily.controller

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

/**
 * 统一轻提示：优先 Snackbar（应用 Widget.Daily.Snackbar 主题），无稳定 View / 非 UI 场景降级 Toast。
 *
 * 背景：业务里大量提示发生在 MQTT 回调线程，Snackbar.make().show() 直接调用会抛
 * CalledFromWrongThreadException（ViewRootImpl 要求 UI 线程操作 View）。故所有入口都 post 到主线程，
 * 并在真正展示时再判断 anchor 是否 attached——这样回调线程也能安全调用。
 *
 * ⚠️ anchor 解析（findViewById / getView）也必须发生在主线程内：View 树不是线程安全的，
 * 在回调线程遍历可能与主线程的 addView/removeView 并发，读到不一致状态。因此 Activity 便捷入口
 * 只在 post 内部才 findViewById。
 *
 * 用法：UiNotice.show(activity, msg) 或 UiNotice.show(ctx, anchorView, msg) 或 UiNotice.toast(ctx, msg)。
 */
object UiNotice {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 时长与 Toast 对齐：LONG 用 Snackbar.LENGTH_LONG，否则 LENGTH_SHORT */
    fun show(
        context: Context?,
        anchor: View?,
        message: String,
        long: Boolean = false
    ) {
        val ctx = context ?: return
        mainHandler.post {
            if (anchor != null && anchor.isAttachedToWindow) {
                showSnackbarOrToast(ctx, anchor, message, long)
            } else {
                toastNow(ctx, message, long)
            }
        }
    }

    /**
     * Activity 便捷入口：anchor 在 post 内部解析，保证只在主线程访问 View 树。
     * Activity 已销毁时 android.R.id.content 可能为 null → 自动降级 Toast。
     */
    fun show(activity: Activity, message: String, long: Boolean = false) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                toastNow(activity, message, long)
                return@post
            }
            val anchor = runCatching { activity.findViewById<View>(android.R.id.content) }.getOrNull()
            if (anchor != null && anchor.isAttachedToWindow) {
                showSnackbarOrToast(activity, anchor, message, long)
            } else {
                toastNow(activity, message, long)
            }
        }
    }

    /** 显式 Toast 降级（Service、无 View、纯后台回调等场景） */
    fun toast(context: Context?, message: String, long: Boolean = false) {
        val ctx = context ?: return
        mainHandler.post { toastNow(ctx, message, long) }
    }

    /** 必须在主线程调用 */
    private fun showSnackbarOrToast(ctx: Context, anchor: View, message: String, long: Boolean) {
        runCatching {
            Snackbar.make(anchor, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
                .show()
        }.onFailure { toastNow(ctx, message, long) }
    }

    /** 必须在主线程调用 */
    private fun toastNow(ctx: Context, message: String, long: Boolean) {
        runCatching {
            Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}
