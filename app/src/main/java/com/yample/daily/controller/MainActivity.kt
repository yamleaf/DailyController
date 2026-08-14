package com.yample.daily.controller

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yample.daily.controller.databinding.ActivityMainBinding
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
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
    /** 当前适配器引用，供在线探测回填（过滤重建后仍指向最新实例） */
    private var currentAdapter: DeviceAdapter? = null
    /** Gson 实例，用于把解绑包序列化为 MQTT 载荷 */
    private val gson = Gson()

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
        // 设置齿轮：控制端 app 级设置（主题外观 / 版本信息）
        binding.btnHeroSettings.setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }
        // 顶栏菜单：客户端管理（配置多个后台并管理在线客户端）
        binding.toolbarMain.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_client_manager) {
                startActivity(Intent(this, ServerlessManagerActivity::class.java))
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
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板为空，请先在被控端「远程控制」页点「生成绑定二维码」（会自动复制到剪贴板）", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val payload = Gson().fromJson(text, BindingPayload::class.java)
            if (payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                addDevice(payload, navigate = true)
            } else {
                Toast.makeText(this, "剪贴板内容缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "剪贴板内容不是有效的配对信息", Toast.LENGTH_SHORT).show()
        }
    }

    /** 扫码添加：用 ScanContract 替代已废弃的 IntentIntegrator */
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val payload = Gson().fromJson(contents, BindingPayload::class.java)
            if (payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
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

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeUpdateRunnable)
        super.onDestroy()
    }

    /** 在线状态探测复用 TTL：30s 内不重复探测（缓存见 OnlineStateCache，与离线监测服务共享） */
    private val PROBE_TTL_MS = 30_000L

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
                selectedGroup.isNotEmpty() -> "在设备列表左滑「编辑」为该分组添加设备"
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
            onMove = { device, delta -> moveDevice(device, delta) }
        ).also { it.openLayoutForPosition = { pos ->
            binding.rvDevices.findViewHolderForAdapterPosition(pos)
                ?.itemView as? com.yample.daily.controller.widget.SwipeRevealLayout
        } }
    }

    /**
     * A1：临时连一次 MQTT，订阅 dt/{id}/status（retained）读取在线/离线状态回填列表卡片。
     * 收到 "online"→true / "offline"→false / 其它或超时→null（未知）。完成后断开并关闭连接。
     * 个人场景设备数少（≤5），N 次短连接可接受；超时控制在 3s 内不卡 UI。
     */
    private fun probeOnline(device: DeviceRecord, adapter: DeviceAdapter) {
        lifecycleScope.launch(Dispatchers.IO) {
            var result: Boolean? = null
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
                val latch = java.util.concurrent.CompletableFuture<String>()
                client.setCallback(object : MqttCallback {
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.payload?.let { latch.complete(String(it).trim()) }
                    }
                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client.connect(opts)
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status", 1)
                val got = try {
                    latch.get(3, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
                result = when (got) {
                    "online" -> true
                    "offline" -> false
                    else -> null
                }
                client.disconnect()
                client.close()
            } catch (_: Exception) {
                result = null
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

    /**
     * 上移/下移：在完整有序列表（pinned DESC, sortOrder ASC）中与相邻项交换 sortOrder 后重查库。
     * 以全局列表为准（而非当前过滤/搜索视图），边界处直接忽略。
     */
    private fun moveDevice(device: DeviceRecord, delta: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ordered = db.deviceDao().getAll()
            val idx = ordered.indexOfFirst { it.deviceId == device.deviceId }
            if (idx < 0) return@launch
            val target = idx + delta
            if (target < 0 || target >= ordered.size) return@launch
            val from = ordered[idx]
            val to = ordered[target]
            db.deviceDao().update(from.copy(sortOrder = to.sortOrder))
            db.deviceDao().update(to.copy(sortOrder = from.sortOrder))
            withContext(Dispatchers.Main) { loadDevices() }
        }
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
