package com.yample.daily.controller

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yample.daily.controller.databinding.ActivityMainBinding
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    /** 当前设备列表快照，供滑动删除回调按位置取设备 */
    private var deviceList: List<DeviceRecord> = emptyList()
    /** 分组过滤：当前选中的分组名（空 = 全部） */
    private var selectedGroup: String = ""
    /** 搜索关键词（空 = 不按关键词过滤） */
    private var searchQuery: String = ""
    private val TAG = "MainActivity"

    /** 当前适配器引用，供在线探测回填（过滤重建后仍指向最新实例） */
    private var currentAdapter: DeviceAdapter? = null
    /** Gson 实例，用于把解绑包序列化为 MQTT 载荷 */
    private val gson = Gson()

    /** 探活解析用 Gson：需注册 PacketValue 适配器才能反序列化 MQTT 状态信封（CMD_STATUS） */
    private val probeGson = GsonBuilder()
        .registerTypeAdapter(PacketValue::class.java, PacketValueAdapter)
        .create()

    /** 分组选择器里「新建分组」的哨兵值（不会与真实分组名冲突） */
    private val newGroupSentinel = "\u0000new_group\u0000"

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒刷新顶栏时间日期（参考被控端格式：title=时间，subtitle=日期） */
    private val timeUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date())
            val currentDate = java.text.SimpleDateFormat("yyyy年MM月dd日 EEEE", java.util.Locale.CHINA)
                .format(java.util.Date())
            binding.toolbarMain.title = currentTime
            binding.toolbarMain.subtitle = currentDate
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15+ 强制 edge-to-edge：给 AppBar 加状态栏高度 padding，避免时钟与系统时间重叠
        UiInsets.applyStatusBarPadding(this, binding.appBarMain)

        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)

        binding.fabAdd.setOnClickListener { showAddChooser() }

        // 处理分享过来的配对信息（ACTION_SEND，跨应用绕过剪贴板限制）
        handleSendTextIntent(intent)

        // 设备列表下拉刷新：重新读库 + 重新探测在线状态
        binding.swipeRefresh.setOnRefreshListener { loadDevices() }

        // 顶栏显示当前时间日期（参考被控端），移除菜单设置入口
        mainHandler.post(timeUpdateRunnable)

        binding.btnEmptyScan.setOnClickListener { startScan() }

        // Hero 头部卡接线
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        binding.tvHeroGreeting.text = when {
            hour < 6 -> "夜深了"
            hour < 12 -> "上午好"
            hour < 14 -> "中午好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
        // Hero 卡图标：客户端管理（配置多个后台并管理在线客户端）
        binding.btnHeroSettings.setOnClickListener {
            startActivity(Intent(this, ServerlessManagerActivity::class.java))
        }
        // 顶栏菜单：控制端 app 级设置（主题外观 / 版本信息）
        binding.toolbarMain.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_app_settings) {
                startActivity(Intent(this, AppSettingsActivity::class.java))
                true
            } else {
                false
            }
        }
        binding.toolbarMain.inflateMenu(R.menu.menu_main)
        // 统计数字初始值（loadDevices 后会更新）
        binding.tvStatOnline.text = "0"
        binding.tvStatTotal.text = "0"
        binding.tvStatOffline.text = "0"

        // 搜索：输入即按名称 / ID 过滤设备列表，并实时切换清空按钮与空状态
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                applyFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.text.clear()
            binding.etSearch.clearFocus()
        }

        // 左滑操作面板：列表滚动时自动收起（避免滑动与展开态冲突）
        binding.rvDevices.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                rv: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                if (newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    (rv.adapter as? DeviceAdapter)?.closeAll()
                }
            }
        })

        loadDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("trigger_add_device", false)) {
            showAddChooser()
        }
        handleSendTextIntent(intent)
    }

    /** 现代化底部弹窗：扫码添加（直接调起扫码，不二次选择）/ 从剪贴板导入（两种方式供用户选） */
    private fun showAddChooser() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetBinding = com.yample.daily.controller.databinding.BottomSheetAddDeviceBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)
        sheetBinding.sheetScan.setOnClickListener {
            sheet.dismiss()
            startScan()
        }
        sheetBinding.sheetClipboard.setOnClickListener {
            sheet.dismiss()
            importFromClipboard()
        }
        sheetBinding.sheetCancel.setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** 剪贴板配对：读取被控端「生成绑定二维码」时复制到剪贴板的配对信息（同一份 JSON），解析后写库并进入控制页自动配对 */
    private fun importFromClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
                Toast.makeText(this, "剪贴板服务不可用", Toast.LENGTH_SHORT).show(); return
            }
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "剪贴板为空，请先在被控端「远程控制」页点「生成绑定二维码」（会自动复制到剪贴板）", Toast.LENGTH_LONG).show()
                return
            }
            Log.d(TAG, "importFromClipboard: $text")
            try {
                val payload = gson.fromJson(text, BindingPayload::class.java)
                if (payload != null && payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                    Log.d(TAG, "解析成功: broker=${payload.broker} deviceId=${payload.deviceId}")
                    addDevice(payload, navigate = true)
                } else {
                    Log.w(TAG, "解析结果字段为空: payload=$payload broker=${payload?.broker} deviceId=${payload?.deviceId}")
                    Toast.makeText(this, "剪贴板内容缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON解析失败: ${e.message}", e)
                Toast.makeText(this, "剪贴板内容不是有效的配对信息", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取剪贴板失败: ${e.message}", e)
            Toast.makeText(this, "读取剪贴板失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理系统分享面板传递过来的配对信息（ACTION_SEND text/plain），
     *  绕开 Android 10+ 跨应用剪贴板读取限制 */
    private fun handleSendTextIntent(intent: Intent) {
        if (Intent.ACTION_SEND != intent.action) return
        if ("text/plain" != intent.type) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        Log.d(TAG, "handleSendTextIntent: $text")
        try {
            val payload = gson.fromJson(text, BindingPayload::class.java)
            if (payload != null && payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                Log.d(TAG, "解析成功: broker=${payload.broker} deviceId=${payload.deviceId}")
                addDevice(payload, navigate = true)
            } else {
                Log.w(TAG, "分享内容缺少必要字段: payload=$payload")
                Toast.makeText(this, "分享内容缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "分享内容解析失败: ${e.message}", e)
            Toast.makeText(this, "分享内容不是有效的配对信息", Toast.LENGTH_SHORT).show()
        }
    }

    /** 扫码添加：用 ScanContract 替代已废弃的 IntentIntegrator */
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val payload = Gson().fromJson(contents, BindingPayload::class.java)
            if (payload != null && payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                addDevice(payload, navigate = true)
            } else {
                Toast.makeText(this, "二维码缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无效的二维码格式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScan() {
        barcodeLauncher.launch(
            ScanOptions()
                .setCaptureActivity(ScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        )
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
        // 离线通知开关开启时，回到前台确保监测服务在跑（进程被杀后由 START_STICKY 或此处拉起）
        if (OfflineMonitorService.isEnabled(this)) {
            try {
                OfflineMonitorService.startCompat(this)
            } catch (_: Exception) {
            }
        }
    }

    /** 离开列表页（切到其他页面 / 退到后台）：清空本次停留的会话验证标记，下次进入重新发 QUERY 校验 */
    override fun onStop() {
        sessionCheckedThisVisit.clear()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeUpdateRunnable)
        super.onDestroy()
    }

    /** 在线状态探测复用 TTL：30s 内不重复探测（缓存见 OnlineStateCache，与离线监测服务共享） */
    private val PROBE_TTL_MS = 30_000L

    /** 会话有效性验证（主动 QUERY）：每次进入列表页对在线设备发一次；本次停留（未切页/未退后台）内不重复发送 */
    private val sessionCheckedThisVisit = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 当前在线状态（deviceId → Boolean?），统计与搜索重建 adapter 后仍保留 */
    private val onlineState = mutableMapOf<String, Boolean?>()

    /** 刷新 Hero 统计：总数 / 已配对在线 / 异常（总设备 - 在线） */
    private fun updateStats() {
        val total = deviceList.size
        val online = deviceList.count {
            it.sessionSecret.isNotBlank() && it.bound && onlineState[it.deviceId] == true
        }
        binding.tvStatOnline.text = online.toString()
        binding.tvStatTotal.text = total.toString()
        binding.tvStatOffline.text = (total - online).toString()
    }

    private fun loadDevices() {
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = db.deviceDao().getAll()
            withContext(Dispatchers.Main) {
                deviceList = devices
                binding.swipeRefresh.isRefreshing = false
                updateGroupChips()
                applyFilters()
                // A1：对每个已配对设备发一次短连接探测在线状态（订阅 dt/{id}/status retained），
                // 30s 内已探测过的设备跳过，避免每次 onResume 都重建 N 条 MQTT 连接
                val now = System.currentTimeMillis()
                devices.filter { it.sessionSecret.isNotBlank() && it.bound }
                    .forEach { device ->
                        val cached = OnlineStateCache.get(device.deviceId)
                        if (cached == null || now - cached.second > PROBE_TTL_MS) {
                            probeOnline(device, currentAdapter ?: return@forEach)
                        } else {
                            onlineState[device.deviceId] = cached.first
                            currentAdapter?.setOnline(device.deviceId, cached.first)
                            // 缓存在线但本次进入尚未验证会话：仍补发一次签名 QUERY 校验绑定状态
                            if (cached.first == true && device.sessionSecret.isNotBlank() && device.bound &&
                                sessionCheckedThisVisit.add(device.deviceId)
                            ) {
                                sessionQueryOnly(device)
                            }
                        }
                    }
                updateStats()
            }
        }
    }

    /** 空状态出现时淡入 + 轻微放大，离开时淡出，比硬显隐更顺滑 */
    private fun animateEmptyState(visible: Boolean) {
        val v = binding.emptyState
        if (visible) {
            if (v.visibility == View.VISIBLE) return
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            // 空状态引导：立即扫码按钮脉冲动画，强化引导
            binding.btnEmptyScan.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse)
            )
        } else {
            if (v.visibility == View.GONE) return
            binding.btnEmptyScan.clearAnimation()
            v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    /** 按分组 + 关键词组合过滤设备列表，刷新 RecyclerView、分组条与空状态 */
    private fun applyFilters() {
        val q = searchQuery.trim()
        val hasQuery = q.isNotEmpty()
        binding.btnSearchClear.visibility = if (hasQuery) View.VISIBLE else View.GONE
        var filtered = deviceList
        if (selectedGroup.isNotEmpty()) filtered = filtered.filter { it.group == selectedGroup }
        if (hasQuery) {
            filtered = filtered.filter {
                it.name.contains(q, ignoreCase = true) || it.deviceId.contains(q, ignoreCase = true)
            }
        }
        val adapter = buildDeviceAdapter(filtered)
        currentAdapter = adapter
        // 搜索/分组重建 adapter 后回填已探测的在线状态，避免状态药丸复位为未知
        onlineState.forEach { (id, state) -> adapter.setOnline(id, state) }
        binding.rvDevices.adapter = adapter
        attachDragReorder(adapter)
        val empty = filtered.isEmpty()
        // 空状态区分：无设备 / 分组无设备 / 搜索无结果（后两者隐藏「立即扫码」）
        binding.btnEmptyScan.visibility = if (hasQuery || selectedGroup.isNotEmpty()) View.GONE else View.VISIBLE
        if (empty) {
            binding.tvEmptyTitle.text = when {
                hasQuery -> "未找到匹配设备"
                selectedGroup.isNotEmpty() -> "该分组暂无设备"
                else -> "还没有绑定设备"
            }
            binding.tvEmptySubtitle.text = when {
                hasQuery -> getString(R.string.search_empty_subtitle, q)
                selectedGroup.isNotEmpty() -> "在设备列表左滑「分组」为该分组添加设备"
                else -> "扫描被控端生成的二维码\n即可远程控制你的打卡设备"
            }
        }
        animateEmptyState(empty)
        binding.rvDevices.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** 重建分组过滤条：全部 + 各非空分组（无分组设备时整条隐藏） */
    private fun updateGroupChips() {
        val groups = deviceList.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
        if (deviceList.isEmpty() || groups.isEmpty()) {
            binding.groupBar.visibility = View.GONE
            return
        }
        binding.groupBar.visibility = View.VISIBLE
        binding.groupChips.removeAllViews()
        addGroupChip("全部", "", deviceList.size)
        groups.forEach { group ->
            val count = deviceList.count { it.group == group }
            addGroupChip(group, group, count)
        }
    }

    private fun addGroupChip(label: String, value: String, count: Int) {
        val isSelected = (selectedGroup == value)
        val ctx = this
        val chip = com.google.android.material.chip.Chip(this).apply {
            text = if (count > 0) "$label $count" else label
            isCheckable = false
            if (isSelected) {
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.md_primary)
                )
                chipStrokeWidth = 0f
                setTextColor(ContextCompat.getColor(ctx, R.color.md_onPrimary))
            } else {
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.md_surfaceVariant)
                )
                chipStrokeWidth = 1f
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.md_outlineVariant)
                )
                setTextColor(ContextCompat.getColor(ctx, R.color.md_onSurfaceVariant))
            }
            setOnClickListener {
                if (selectedGroup != value) {
                    selectedGroup = value
                    updateGroupChips()
                    applyFilters()
                }
            }
            // 长按真实分组 → 重命名 / 删除分组（「全部」除外）
            if (value.isNotEmpty()) {
                setOnLongClickListener {
                    showGroupManageDialog(value)
                    true
                }
            }
        }
        binding.groupChips.addView(chip)
    }

    /** 分组管理：重命名 / 删除（删除后组内设备回到未分组） */
    private fun showGroupManageDialog(group: String) {
        UnifiedDialogKit.showMenu(
            ctx = this,
            title = "分组「$group」",
            items = listOf("重命名分组", "删除分组")
        ) { which ->
            when (which) {
                0 -> renameGroup(group)
                1 -> deleteGroup(group)
            }
        }
    }

    private fun renameGroup(group: String) {
        val dlgBinding = com.yample.daily.controller.databinding.DialogGroupBinding.inflate(layoutInflater)
        dlgBinding.etGroupName.setText(group)
        dlgBinding.etGroupName.setSelection(group.length)
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = dlgBinding.root,
            title = "重命名分组",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val newGroup = dlgBinding.etGroupName.text.toString().trim()
                if (newGroup.isEmpty() || newGroup == group) {
                    false // 未变更：不关闭弹窗
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        deviceList.filter { it.group == group }
                            .forEach { db.deviceDao().update(it.copy(group = newGroup)) }
                        withContext(Dispatchers.Main) {
                            selectedGroup = newGroup
                            loadDevices()
                        }
                    }
                    true
                }
            }
        )
    }

    private fun deleteGroup(group: String) {
        showDestructiveConfirm(
            this,
            title = "删除分组",
            message = "确定删除分组「$group」？该分组下的设备将变为未分组。",
            confirmText = "删除"
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                deviceList.filter { it.group == group }
                    .forEach { db.deviceDao().update(it.copy(group = "")) }
                withContext(Dispatchers.Main) {
                    selectedGroup = ""
                    loadDevices()
                }
            }
        }
    }

    private fun buildDeviceAdapter(list: List<DeviceRecord>): DeviceAdapter {
        return DeviceAdapter(list,
            onClick = { device ->
                val intent = Intent(this@MainActivity, DeviceControlActivity::class.java)
                intent.putExtra("deviceId", device.deviceId)
                startActivity(intent)
            },
            onRename = { device -> renameDevice(device) },
            onPin = { device -> togglePin(device) },
            onDelete = { device -> confirmDeleteDevice(device) },
            onGroup = { device -> assignGroup(device) }
        ).also { it.openLayoutForPosition = { pos ->
            binding.rvDevices.findViewHolderForAdapterPosition(pos)
                ?.itemView as? com.yample.daily.controller.widget.SwipeRevealLayout
        } }
    }

    /**
     * A1：临时连一次 MQTT，订阅 dt/{id}/status（retained）读取在线/离线状态回填列表卡片。
     * 收到 "online"→true / "offline"→false / 其它或超时→null（未知）。
     * 若设备处于「在线·已绑定」，且本次进入尚未验证过会话，再主动发一条带本端会话签名的 QUERY：
     * 被控端若已与其他手机重新配对（换绑），会因会话不匹配回 SIGN_FAIL，据此判定解绑
     * （解决「被控端已解绑换绑、列表仍显示在线·绑定」：探活只读 retained 拿不到被覆盖的信封）。
     * 完成后断开并关闭连接。个人场景设备数少（≤5），N 次短连接可接受；单次等待 ≤3s 不卡 UI。
     */
    private fun probeOnline(device: DeviceRecord, adapter: DeviceAdapter) {
        // 已解绑/未配对设备：不再主动连接打扰被控端，状态由 DB 的 bound 标志直接呈现
        if (!device.bound || device.sessionSecret.isBlank()) {
            lifecycleScope.launch(Dispatchers.Main) {
                onlineState[device.deviceId] = null
                adapter.setOnline(device.deviceId, null)
            }
            return
        }
        // HB 快速路径：被控端 presence 心跳足够新鲜（<3 分钟）且会话校验未过期 → 直接判在线，
        // 免去一次完整 MQTT 短连接探活；HB 过期/缺失，或会话校验到期（需验签识别解绑/换绑）才回落常规探活。
        // 关键：不能无限跳过会话校验，否则解绑/换绑设备因 HB 持续在发而永远误判「在线」。
        val hbFresh = OnlineStateCache.hbAgeMs(device.deviceId)?.let { it < OnlineStateCache.HB_FRESH_MS } == true
        val checkAge = OnlineStateCache.sessionCheckAgeMs(device.deviceId)
        val needSessionCheck = checkAge == null || checkAge >= OnlineStateCache.SESSION_CHECK_INTERVAL_MS
        if (hbFresh && !needSessionCheck) {
            OnlineStateCache.put(device.deviceId, true, System.currentTimeMillis())
            lifecycleScope.launch(Dispatchers.Main) {
                onlineState[device.deviceId] = true
                adapter.setOnline(device.deviceId, true)
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var result: Boolean? = null
            var unbound = false
            try {
                val client = MqttClient(
                    BrokerUtils.normalizeBroker(device.broker),
                    "ctl-probe-${device.deviceId}",
                    MemoryPersistence()
                )
                val opts = MqttConnectOptions().apply {
                    userName = device.ctlUser
                    password = device.ctlPass.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 5
                }
                val statusLatch = java.util.concurrent.CompletableFuture<String>()
                val ackLatch = java.util.concurrent.CompletableFuture<String>()
                var pendingRid: String? = null
                client.setCallback(object : MqttCallback {
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.let { String(it).trim() } ?: return
                        when (topic?.substringAfterLast('/')) {
                            "status" -> statusLatch.complete(payload)
                            // 只响应会话校验发出的 rid 对应的回执，避免迟到的无关 ack 抢先完成 latch
                            "ack" -> if (pendingRid != null && parseRid(payload) == pendingRid) {
                                ackLatch.complete(payload)
                            }
                            // 顺路记录 HB 心跳时间戳，供下次探活走快速路径
                            "presence" -> OnlineStateCache.noteHb(device.deviceId, payload)
                        }
                    }
                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client.connect(opts)
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status", 1)
                // presence 的 retained HB 会立即送达，顺路刷新 HB 缓存
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/presence", 1)
                val got = try {
                    statusLatch.get(3, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
                when (val parsed = parseProbeStatus(got, device)) {
                    ProbeResult.Unbound -> {
                        // 收到被控端签名解绑状态（force_unbound/unbound）：本地绑定已失效
                        unbound = true
                        result = null
                    }
                    is ProbeResult.Online -> result = parsed.value
                }
                // 拿到 retained 状态后立即回填在线药丸（不等 QUERY 会话校验），避免列表长期停在「未知等待」
                if (!unbound) {
                    val early = result
                    withContext(Dispatchers.Main) {
                        onlineState[device.deviceId] = early
                        adapter.setOnline(device.deviceId, early)
                    }
                }
                // 会话有效性验证：仅当设备在线且已绑定，且本次进入尚未验证过才主动 QUERY。
                // 换绑后 retained 被明文 "online" 覆盖，探活读不到信封，只能靠签名 QUERY 的 SIGN_FAIL 识别。
                if (!unbound && result == true && device.sessionSecret.isNotBlank() && device.bound &&
                    sessionCheckedThisVisit.add(device.deviceId)
                ) {
                    val ts = System.currentTimeMillis()
                    val rid = UUID.randomUUID().toString()
                    pendingRid = rid
                    val sign = MqttSigner.sign(
                        device.sessionSecret, device.deviceId, ts, rid,
                        "snapshot", "", "", MqttPacket.CMD_QUERY
                    )
                    val packet = MqttPacket(
                        c = MqttPacket.CMD_QUERY, f = "snapshot", v = null,
                        rid = rid, ts = ts, sign = sign
                    )
                    client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack", 1)
                    try {
                        client.publish(
                            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                            MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
                        )
                    } catch (_: Exception) {
                    }
                    val ack = try {
                        ackLatch.get(3, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        null
                    }
                    val code = ack?.let(::parseAckCode)
                    if (code == "SIGN_FAIL" || code == "UNBOUND") {
                        // 设备回 SIGN_FAIL/UNBOUND：本端会话已失效（被控端已解绑或与其他手机换绑）
                        unbound = true
                        result = null
                    } else {
                        // 验签通过：会话有效，记录校验时间，此后 HB 快速路径可继续复用
                        OnlineStateCache.noteSessionChecked(device.deviceId)
                    }
                }
                client.disconnect()
                client.close()
            } catch (_: Exception) {
                result = null
            }
            if (unbound) {
                applyUnbound(device)
                return@launch
            }
            val final = result
            val prev = OnlineStateCache.get(device.deviceId)?.first
            OnlineStateCache.put(device.deviceId, final, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                onlineState[device.deviceId] = final
                adapter.setOnline(device.deviceId, final)
                updateStats()
                // 在线→离线跃迁：广播给 OfflineMonitorService 统一弹通知 + 记录
                if (prev == true && final == false) {
                    sendBroadcast(Intent(OfflineMonitorService.ACTION_DEVICE_OFFLINE).apply {
                        putExtra("deviceName", device.name)
                        putExtra("deviceId", device.deviceId)
                        putExtra("ts", System.currentTimeMillis())
                    })
                }
            }
        }
    }

    /** 探活状态解析结果：Online 携带在线态（true/false/null），Unbound=检测到签名解绑状态 */
    private sealed class ProbeResult {
        data class Online(val value: Boolean?) : ProbeResult()
        object Unbound : ProbeResult()
    }

    /**
     * 仅做会话有效性校验的短连接：缓存显示在线、但本次进入尚未验证会话时调用。
     * 发一条带本端会话签名的 QUERY，被控端若已换绑/解绑会回 SIGN_FAIL/UNBOUND，据此清本地绑定。
     */
    private fun sessionQueryOnly(device: DeviceRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            var unbound = false
            try {
                val client = MqttClient(
                    BrokerUtils.normalizeBroker(device.broker),
                    "ctl-sess-${device.deviceId}",
                    MemoryPersistence()
                )
                val opts = MqttConnectOptions().apply {
                    userName = device.ctlUser
                    password = device.ctlPass.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 5
                }
                val ackLatch = java.util.concurrent.CompletableFuture<String>()
                var pendingRid: String? = null
                client.setCallback(object : MqttCallback {
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.let { String(it).trim() } ?: return
                        if (topic?.endsWith("/ack") == true && pendingRid != null && parseRid(payload) == pendingRid) {
                            ackLatch.complete(payload)
                        }
                    }
                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client.connect(opts)
                val ts = System.currentTimeMillis()
                val rid = UUID.randomUUID().toString()
                pendingRid = rid
                val sign = MqttSigner.sign(
                    device.sessionSecret, device.deviceId, ts, rid,
                    "snapshot", "", "", MqttPacket.CMD_QUERY
                )
                val packet = MqttPacket(
                    c = MqttPacket.CMD_QUERY, f = "snapshot", v = null,
                    rid = rid, ts = ts, sign = sign
                )
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack", 1)
                try {
                    client.publish(
                        "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                        MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
                    )
                } catch (_: Exception) {
                }
                val ack = try {
                    ackLatch.get(3, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
                val code = ack?.let(::parseAckCode)
                if (code == "SIGN_FAIL" || code == "UNBOUND") unbound = true
                client.disconnect()
                client.close()
            } catch (_: Exception) {
            }
            if (unbound) applyUnbound(device)
        }
    }

    /** 设备已解绑/换绑：清除本地绑定态并刷新列表，让列表正确显示「解绑」而非残留「在线·绑定」 */
    private suspend fun applyUnbound(device: DeviceRecord) {
        val updated = device.copy(sessionSecret = "", pairingToken = "", bound = false)
        db.deviceDao().update(updated)
        // 解绑：MQTT 连接开关持久化关闭，详情页打开时不再自动连接（仅重新扫码配对会重新开启）
        runCatching {
            getSharedPreferences("remote_ctrl", MODE_PRIVATE)
                .edit().putBoolean("remote_enabled_${device.deviceId}", false).apply()
        }
        OnlineStateCache.remove(device.deviceId)
        withContext(Dispatchers.Main) {
            onlineState.remove(device.deviceId)
            loadDevices()
            Toast.makeText(
                this@MainActivity,
                "设备「${device.name}」已被解绑，请重新扫码配对",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 从 ack 载荷提取 rid，用于过滤可能迟到的无关回执（非 JSON 返回 null） */
    private fun parseRid(payload: String): String? = try {
        probeGson.fromJson(payload, MqttPacket::class.java)?.rid
    } catch (_: Exception) {
        null
    }

    /** 提取 ack 载荷的结果码（v 字符串），非 JSON/无值返回 null */
    private fun parseAckCode(payload: String): String? = try {
        probeGson.fromJson(payload, MqttPacket::class.java)?.v?.toStringValue()
    } catch (_: Exception) {
        null
    }

    /**
     * 解析 status 主题载荷：
     * - "online"/"offline" 明文 → 在线/离线
     * - 签名 JSON 信封（CMD_STATUS）且 state 为 unbound/force_unbound → 验签通过则返回 Unbound
     *   （被控端解绑时用旧会话签名后清密钥，本端 sessionSecret 仍在，可验签；防公共 Broker 伪造 plain）
     * - 其余 → 未知
     */
    private fun parseProbeStatus(raw: String?, device: DeviceRecord): ProbeResult {
        when (raw) {
            "online" -> return ProbeResult.Online(true)
            "offline" -> return ProbeResult.Online(false)
        }
        if (raw != null && raw.startsWith("{")) {
            try {
                val packet = probeGson.fromJson(raw, MqttPacket::class.java) ?: return ProbeResult.Online(null)
                if (packet.c != Protocol.CMD_STATUS && packet.c != MqttPacket.CMD_STATUS) {
                    return ProbeResult.Online(null)
                }
                val state = packet.v?.toStringValue() ?: return ProbeResult.Online(null)
                if (state == "unbound" || state == "force_unbound") {
                    if (device.sessionSecret.isNotBlank()) {
                        val expected = MqttSigner.sign(
                            device.sessionSecret, device.deviceId, packet.ts, packet.rid,
                            "", "s", state, Protocol.CMD_STATUS
                        )
                        if (expected == packet.sign) return ProbeResult.Unbound
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ProbeResult.Online(null)
    }

    private fun addDevice(payload: BindingPayload, navigate: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 新建设备追加到非置顶区末尾：sortOrder = 当前最大序号 + 1
            val maxOrder = db.deviceDao().getAll().maxOfOrNull { it.sortOrder } ?: -1
            val device = DeviceRecord(
                deviceId = payload.deviceId,
                name = "设备 ${payload.deviceId}",
                broker = payload.broker,
                ctlUser = payload.ctlUser,
                ctlPass = payload.ctlPass,
                sessionSecret = "",
                pairingToken = payload.pairingToken,
                bound = false,
                sortOrder = maxOrder + 1
            )
            db.deviceDao().insert(device)
            withContext(Dispatchers.Main) {
                loadDevices()
                if (navigate) {
                    val intent = Intent(this@MainActivity, DeviceControlActivity::class.java)
                    intent.putExtra("deviceId", device.deviceId)
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "设备已添加", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 编辑设备：弹输入框预填当前名称/分组，保存后刷新列表（Room @Update），统一底部双按钮等宽均分 */
    private fun renameDevice(device: DeviceRecord) {
        val dlgBinding = com.yample.daily.controller.databinding.DialogRenameBinding.inflate(layoutInflater)
        dlgBinding.etName.setText(device.name)
        dlgBinding.etName.setSelection(device.name.length)
        dlgBinding.etGroup.setText(device.group)
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = dlgBinding.root,
            title = "编辑设备",
            message = "为「${device.deviceId}」设置备注名称与分组",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val newName = dlgBinding.etName.text.toString().trim().ifBlank { device.name }
                val newGroup = dlgBinding.etGroup.text.toString().trim()
                lifecycleScope.launch(Dispatchers.IO) {
                    db.deviceDao().update(device.copy(name = newName, group = newGroup))
                    withContext(Dispatchers.Main) { loadDevices() }
                }
                true
            }
        )
    }

    /** 置顶 / 取消置顶：翻转 pinned 标记并刷新列表（Room @Update + 列表按 pinned 排序） */
    private fun togglePin(device: DeviceRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 取消置顶时追加到非置顶区末尾（sortOrder 旧的可能是历史序号，直接放开会掉到列表中间）
            val unpinAppend = if (device.pinned) {
                val max = db.deviceDao().getAll().maxOfOrNull { it.sortOrder } ?: -1
                max + 1
            } else {
                device.sortOrder
            }
            db.deviceDao().update(device.copy(pinned = !device.pinned, sortOrder = unpinAppend))
            withContext(Dispatchers.Main) { loadDevices() }
        }
    }

    // ===================== 长按拖拽排序 =====================
    // 上移/下移按钮已改为「长按卡片上下拖动」：更符合移动端习惯，也释放左滑面板宽度。
    // 排序本质仍是 sortOrder 重排 —— 不能只交换两个值：旧版升级（v5→v6 迁移给存量设备统一补 DEFAULT 0）
    // 会让多项 sortOrder 重复，ORDER BY 无法区分导致排序失效，因此落定后按显示顺序整体重新编号。

    private var dragTouchHelper: ItemTouchHelper? = null

    /** 挂载长按拖拽排序：仅启用上/下拖，横向左滑仍由 SwipeRevealLayout 处理；置顶边界由适配器阻挡 */
    private fun attachDragReorder(adapter: DeviceAdapter) {
        if (dragTouchHelper == null) {
            dragTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

                // 长按由卡片监听手动触发 startDrag（整卡可拖，无需拖动手柄）
                override fun isLongPressDragEnabled(): Boolean = false
                override fun isItemViewSwipeEnabled(): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val current = recyclerView.adapter as? DeviceAdapter ?: return false
                    return current.onMoveItems(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition
                    )
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.apply {
                            elevation = 12f
                            alpha = 0.95f
                        }
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.apply {
                        elevation = 0f
                        alpha = 1f
                    }
                    (recyclerView.adapter as? DeviceAdapter)
                        ?.currentOrder()?.let { persistOrder(it) }
                }
            }).also { it.attachToRecyclerView(binding.rvDevices) }
        }
        adapter.itemTouchHelper = dragTouchHelper
    }

    /** 把列表按显示顺序整体重新编号写库（sortOrder = 顺序下标），保证唯一且与界面顺序一致 */
    private fun persistOrder(ordered: List<DeviceRecord>) {
        lifecycleScope.launch(Dispatchers.IO) {
            ordered.forEachIndexed { index, item ->
                if (item.sortOrder != index) {
                    db.deviceDao().update(item.copy(sortOrder = index))
                }
            }
            withContext(Dispatchers.Main) { loadDevices() }
        }
    }

    // ===================== 左滑操作面板的「分组」按钮 =====================
    // 分组选择器：已有分组 + 未分组 + 新建分组，选中即写入并刷新（统一化列表弹窗）
    private fun assignGroup(device: DeviceRecord) {
        val groups = deviceList.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
        val labels = ArrayList<String>()
        labels.add("未分组")
        labels.addAll(groups)
        labels.add("＋ 新建分组…")
        UnifiedDialogKit.showMenu(
            ctx = this,
            title = "「${device.name}」分组",
            items = labels,
            onSelect = { index ->
                val value = when {
                    index == 0 -> ""
                    index == labels.size - 1 -> newGroupSentinel
                    else -> groups[index - 1]
                }
                if (value == newGroupSentinel) {
                    showCreateGroupDialog(device)
                } else {
                    applyGroup(device, value)
                }
            }
        )
    }

    /** 直接写入设备分组并刷新列表 */
    private fun applyGroup(device: DeviceRecord, group: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.deviceDao().update(device.copy(group = group))
            withContext(Dispatchers.Main) { loadDevices() }
        }
    }

    /** 新建分组：复用「重命名分组」的单输入框弹窗，创建后把设备归入新分组 */
    private fun showCreateGroupDialog(device: DeviceRecord) {
        val dlgBinding = com.yample.daily.controller.databinding.DialogGroupBinding.inflate(layoutInflater)
        dlgBinding.etGroupName.setText("")
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = dlgBinding.root,
            title = "新建分组",
            message = "输入新分组名称，将「${device.name}」归入该分组",
            positiveText = "创建",
            negativeText = "取消",
            onConfirm = {
                val group = dlgBinding.etGroupName.text.toString().trim()
                if (group.isEmpty()) {
                    false
                } else {
                    applyGroup(device, group)
                    true
                }
            }
        )
    }

    // ===================== 左滑操作面板的「删除」按钮 =====================
    // D1：删除即删即解绑极易误触 —— 先弹确认框，确认才解绑+删除，取消则保持列表不变
    private fun confirmDeleteDevice(device: DeviceRecord) {
        showDestructiveConfirm(
            this,
            title = "解绑并删除",
            message = "确定从本机移除「${device.name}」？被控端也会收到解绑通知并清除绑定。",
            confirmText = "删除"
        ) {
            // 1) 先通知被控端解绑（与被控端「解绑设备」同源：发布 UB 到 dt/{id}/pair）
            publishUnbindToControlled(device)
            // 2) 再删除本机记录
            lifecycleScope.launch(Dispatchers.IO) {
                db.deviceDao().deleteById(device.deviceId)
                OnlineStateCache.remove(device.deviceId)
                withContext(Dispatchers.Main) {
                    loadDevices()
                    Toast.makeText(this@MainActivity, "已删除设备并通知被控端解绑", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 删除设备时，临时连一次 MQTT 把 UB 命令发到被控端 dt/{id}/pair，使其自动解绑 */
    private fun publishUnbindToControlled(device: DeviceRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = MqttClient(
                    BrokerUtils.normalizeBroker(device.broker),
                    "ctl-del-${device.deviceId}",
                    MemoryPersistence()
                )
                val opts = MqttConnectOptions().apply {
                    userName = device.ctlUser
                    password = device.ctlPass.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 8
                }
                client.connect(opts)
                val ts = System.currentTimeMillis()
                val rid = UUID.randomUUID().toString()
                val sign = if (device.sessionSecret.isNotBlank()) {
                    MqttSigner.sign(
                        device.sessionSecret, device.deviceId, ts, rid, "", "s", "", MqttPacket.CMD_UNBOUND
                    )
                } else ""
                val packet = MqttPacket(
                    c = MqttPacket.CMD_UNBOUND,
                    f = "",
                    v = PacketValue.StringValue(""),
                    rid = rid,
                    ts = ts,
                    sign = sign
                )
                client.publish(
                    "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
                )
                client.disconnect()
                client.close()
            } catch (_: Exception) {
                // 通知失败不影响本机删除：被控端下次上线会因会话状态不一致而自然失配
            }
        }
    }
}
