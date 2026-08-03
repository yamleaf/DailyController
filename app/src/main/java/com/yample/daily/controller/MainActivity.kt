package com.yample.daily.controller

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.gson.Gson
import com.google.zxing.integration.android.IntentIntegrator
import com.yample.daily.controller.databinding.ActivityMainBinding
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.Protocol
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
    /** Gson 实例，用于把解绑包序列化为 MQTT 载荷 */
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .fallbackToDestructiveMigration()
            .build()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        attachSwipeToDelete()

        binding.fabAdd.setOnClickListener { showAddChooser() }
        binding.btnEmptyScan.setOnClickListener { startScan() }
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

    private fun startScan() {
        IntentIntegrator(this)
            .setCaptureActivity(ScanActivity::class.java)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .initiateScan()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    /** A1：在线状态探测结果缓存（deviceId → Pair<结果, 时间戳>），30s 内不重复探测 */
    private val probeCache = mutableMapOf<String, Pair<Boolean?, Long>>()
    private val PROBE_TTL_MS = 30_000L

    private fun loadDevices() {
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = db.deviceDao().getAll()
            withContext(Dispatchers.Main) {
                deviceList = devices
                val adapter = DeviceAdapter(devices) { device ->
                    val intent = Intent(this@MainActivity, DeviceControlActivity::class.java)
                    intent.putExtra("deviceId", device.deviceId)
                    startActivity(intent)
                }
                binding.rvDevices.adapter = adapter
                binding.toolbarMain.subtitle = "共 ${devices.size} 台设备"
                binding.emptyState.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDevices.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
                // A1：对每个已配对设备发一次短连接探测在线状态（订阅 dt/{id}/status retained），
                // 30s 内已探测过的设备跳过，避免每次 onResume 都重建 N 条 MQTT 连接
                val now = System.currentTimeMillis()
                devices.filter { it.sessionSecret.isNotBlank() && it.bound }
                    .forEach { device ->
                        val cached = probeCache[device.deviceId]
                        if (cached == null || now - cached.second > PROBE_TTL_MS) {
                            probeOnline(device, adapter)
                        } else {
                            adapter.setOnline(device.deviceId, cached.first)
                        }
                    }
            }
        }
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
            probeCache[device.deviceId] = final to System.currentTimeMillis()
            withContext(Dispatchers.Main) { adapter.setOnline(device.deviceId, final) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            try {
                val payload = Gson().fromJson(result.contents, BindingPayload::class.java)
                if (payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                    addDevice(payload)
                } else {
                    Toast.makeText(this, "二维码缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "无效的二维码格式", Toast.LENGTH_SHORT).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun addDevice(payload: BindingPayload, navigate: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val device = DeviceRecord(
                deviceId = payload.deviceId,
                name = "设备 ${payload.deviceId}",
                broker = payload.broker,
                ctlUser = payload.ctlUser,
                ctlPass = payload.ctlPass,
                sessionSecret = "",
                pairingToken = payload.pairingToken,
                bound = false
            )
            db.deviceDao().insert(device)
            withContext(Dispatchers.Main) {
                loadDevices()
                if (navigate) {
                    val intent = Intent(this@MainActivity, DeviceControlActivity::class.java)
                    intent.putExtra("deviceId", device.deviceId)
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "设备添加成功，请在设备控制页完成配对", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===================== 滑动删除（左滑显示红色删除按钮） =====================
    private fun attachSwipeToDelete() {
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position !in deviceList.indices) {
                    loadDevices()
                    return
                }
                val device = deviceList[position]
                // D1：滑动删除即删即解绑极易误触 —— 先弹确认框，确认才解绑+删除，取消则还原列表
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("解绑并删除")
                    .setMessage("确定从本机移除「${device.name}」？被控端也会收到解绑通知并清除绑定。")
                    .setPositiveButton("删除") { _, _ ->
                        // 1) 先通知被控端解绑（与被控端「解绑设备」同源：发布 UB 到 dt/{id}/pair）
                        publishUnbindToControlled(device)
                        // 2) 再删除本机记录
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.deviceDao().deleteById(device.deviceId)
                            withContext(Dispatchers.Main) {
                                loadDevices()
                                Toast.makeText(this@MainActivity, "已删除设备并通知被控端解绑", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消") { _, _ -> loadDevices() }
                    .setOnCancelListener { loadDevices() }
                    .show()
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.5f

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val itemView = viewHolder.itemView
                    val density = resources.displayMetrics.density
                    // 仅在“被左滑露出的右侧区域”绘制红色删除背景
                    val left = itemView.right + dX
                    c.save()
                    c.clipRect(left, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
                    c.drawColor(resources.getColor(R.color.md_error, theme))
                    val paint = Paint().apply {
                        color = Color.WHITE
                        textSize = 16f * density
                        textAlign = Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    val text = "删除"
                    val y = itemView.top + itemView.height / 2f + paint.textSize / 3f
                    c.drawText(text, itemView.right - 24f * density, y, paint)
                    c.restore()
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        touchHelper.attachToRecyclerView(binding.rvDevices)
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
                val packet = MqttPacket(
                    c = MqttPacket.CMD_UNBOUND,
                    f = "",
                    v = PacketValue.StringValue(""),
                    rid = UUID.randomUUID().toString(),
                    ts = System.currentTimeMillis(),
                    sign = ""
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
