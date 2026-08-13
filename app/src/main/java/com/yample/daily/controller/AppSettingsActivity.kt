package com.yample.daily.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yample.daily.controller.databinding.ActivityAppSettingsBinding
import com.yample.mqttprotocol.ThemeManager
import com.yample.mqttprotocol.dialog.UnifiedDialogKit

/**
 * 控制端 app 级设置页：主题外观 / 离线通知 / 版本信息等。
 * 采用与设备设置一致的分组卡片布局，后续新增选项在 activity_app_settings.xml 中追加行即可。
 */
class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding
    private val notifyPermission = android.Manifest.permission.POST_NOTIFICATIONS
    private val REQ_NOTIFY = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiInsets.applyStatusBarPadding(this, binding.appBar)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rowTheme.setOnClickListener { showThemeChooser() }
        binding.rowVersion.setOnClickListener { showVersionInfo() }
        // 设置页版本行副标题：只显示 git 短哈希，其余构建信息进弹窗
        binding.tvVersionSummary.text = BuildConfig.GIT_SHA

        // 离线通知开关：默认关闭；开启需通知权限并拉起前台监测服务，关闭则停服务
        binding.switchNotifyOffline.isChecked = OfflineMonitorService.isEnabled(this)
        binding.switchNotifyOffline.setOnCheckedChangeListener { _, on -> handleNotifyToggle(on) }

        // 离线通知：展示最近一次设备离线时间
        binding.tvLastOffline.text = formatLastOffline()

        refreshThemeValue()
    }

    /** 离线通知开关：开启需通知权限（Android 13+ 运行时申请），关闭直接停服务 */
    private fun handleNotifyToggle(on: Boolean) {
        if (!on) {
            OfflineMonitorService.setEnabled(this, false)
            OfflineMonitorService.stopCompat(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, notifyPermission) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(notifyPermission), REQ_NOTIFY)
            return
        }
        enableOfflineNotify()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableOfflineNotify()
            } else {
                binding.switchNotifyOffline.isChecked = false
                Toast.makeText(this, "未授予通知权限，无法开启离线提醒", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableOfflineNotify() {
        OfflineMonitorService.setEnabled(this, true)
        OfflineMonitorService.startCompat(this)
        Toast.makeText(this, "设备通知已开启：告警会话缓存约 2 小时内可补收", Toast.LENGTH_SHORT).show()
    }

    private fun refreshThemeValue() {
        binding.tvThemeValue.text = ThemeManager.LABELS[ThemeManager.getMode(this)]
    }

    /** 读取离线监测服务记录的最近离线时间，格式化展示；无记录则提示暂无。
     * 近期用相对时间（刚刚 / x分钟前 / 今天 / 昨天），更早回退到「MM-dd HH:mm」。
     * 从所有设备中取最新一条，同时显示设备名，便于在多设备场景下区分。 */
    private fun formatLastOffline(): String {
        val (deviceName, ts) = OfflineMonitorService.latestOffline(this)
        if (ts == 0L) return "上次离线时间：暂无记录"
        val now = System.currentTimeMillis()
        val diff = now - ts
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val fullFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val prefix = when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            else -> {
                val cal = java.util.Calendar.getInstance()
                val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
                val sameDay = cal.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                        cal.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
                val yCal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                val yesterday = yCal.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                        yCal.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
                when {
                    sameDay -> "今天 ${timeFmt.format(java.util.Date(ts))}"
                    yesterday -> "昨天 ${timeFmt.format(java.util.Date(ts))}"
                    else -> fullFmt.format(java.util.Date(ts))
                }
            }
        }
        return if (deviceName.isNotBlank()) "上次离线：$deviceName · $prefix" else "上次离线时间：$prefix"
    }

    override fun onResume() {
        super.onResume()
        // 返回设置页或切回前台时刷新：若期间有设备离线，时间展示同步更新
        binding.tvLastOffline.text = formatLastOffline()
        refreshThemeValue()
    }

    /** 主题外观：深色 / 浅色 / 跟随系统（与被控端共用 :protocol 的 ThemeManager） */
    private fun showThemeChooser() {
        val current = ThemeManager.getMode(this)
        UnifiedDialogKit.showSingleChoice(
            ctx = this,
            title = "主题外观",
            items = ThemeManager.LABELS.toList(),
            selectedIndex = current
        ) { which ->
            if (which != current) {
                ThemeManager.setMode(this, which)
                recreate()
            }
        }
    }

    /** 版本信息：列出构建元数据，支持一键复制 */
    private fun showVersionInfo() {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        val targetSdk = appInfo.targetSdkVersion
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0

        val rows = linkedMapOf(
            "Git 提交" to BuildConfig.GIT_SHA,
            "版本号" to BuildConfig.VERSION_NAME,
            "Version Code" to BuildConfig.VERSION_CODE.toString(),
            "构建来源" to BuildConfig.BUILD_SOURCE,
            "构建时间" to BuildConfig.BUILD_TIME,
            "基线版本" to BuildConfig.BASELINE_VERSION,
            "包名" to BuildConfig.APPLICATION_ID,
            "Target SDK" to targetSdk.toString(),
            "Min SDK" to minSdk.toString()
        )

        val sb = StringBuilder()
        rows.forEach { (k, v) -> sb.append("$k：$v\n") }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        rows.forEach { (k, v) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = k
                textSize = 13f
                setTextColor(resources.getColor(R.color.md_onSurfaceVariant, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })
            row.addView(TextView(this).apply {
                text = v
                textSize = 13f
                setTextColor(resources.getColor(R.color.md_onSurface, theme))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(row)
        }

        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = container,
            title = "版本信息",
            message = "轻触「复制全部」一键复制以上信息",
            positiveText = "复制全部",
            negativeText = "关闭",
            onConfirm = {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("版本信息", sb.toString().trimEnd()))
                Toast.makeText(this, "已复制全部版本信息到剪贴板", Toast.LENGTH_SHORT).show()
                true
            }
        )
    }
}
