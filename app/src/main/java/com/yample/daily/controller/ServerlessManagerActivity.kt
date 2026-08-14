package com.yample.daily.controller

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.yample.daily.controller.ServerlessApiClient.ServerlessClient
import com.yample.daily.controller.databinding.ActivityServerlessManagerBinding
import com.yample.daily.controller.databinding.DialogBackendBinding
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.TextView
import android.widget.EditText

/**
 * 控制端「客户端管理」页：从设备列表页顶栏菜单进入。
 * - 后台列表页：配置多个 EMQX Serverless 后台（名称/API地址/AppID/AppSecret）
 * - 客户端管理页：选一个后台，查看/管理其 broker 上的在线客户端（详情/订阅/下线）
 */
class ServerlessManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerlessManagerBinding
    private lateinit var db: AppDatabase
    private var backends: List<ServerlessBackend> = emptyList()
    /** 当前选中的后台 id（null = 未进入客户端页） */
    private var currentBackendId: Long? = null
    private var currentBackend: ServerlessBackend? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerlessManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiInsets.applyStatusBarPadding(this, binding.appBar)

        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        binding.toolbar.setNavigationOnClickListener {
            if (currentBackendId != null) showBackendPage() else finish()
        }

        binding.rvBackends.layoutManager = LinearLayoutManager(this)
        binding.rvClients.layoutManager = LinearLayoutManager(this)

        binding.fabAddBackend.setOnClickListener { showBackendEdit(null) }
        binding.btnSwitchBackend.setOnClickListener { showBackendChooser() }
        binding.btnRefresh.setOnClickListener { loadClients() }

        loadBackends()
    }

    // ══════════════ 后台列表 ══════════════

    private fun loadBackends() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.serverlessBackendDao().getAll()
            withContext(Dispatchers.Main) {
                backends = list
                renderBackends()
                // 若在客户端页且当前后台被删，退回后台列表
                val cur = currentBackendId
                if (cur != null && list.none { it.id == cur }) showBackendPage()
            }
        }
    }

    private fun renderBackends() {
        binding.rvBackends.adapter = BackendAdapter(
            backends,
            onClick = { openBackend(it) },
            onLongClick = { showBackendManage(it) }
        )
        binding.emptyBackend.visibility = if (backends.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBackends.visibility = if (backends.isEmpty()) View.GONE else View.VISIBLE
        // 客户端页的后台 chip 同步名称
        currentBackend?.let { binding.btnSwitchBackend.text = it.name }
    }

    private fun showBackendEdit(target: ServerlessBackend?) {
        val dlgBinding = DialogBackendBinding.inflate(layoutInflater)
        if (target != null) {
            dlgBinding.etName.setText(target.name)
            dlgBinding.etUrl.setText(target.baseUrl)
            dlgBinding.etAppId.setText(target.appId)
            dlgBinding.etAppSecret.setText(target.appSecret)
        }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = dlgBinding.root,
            title = if (target == null) "添加后台" else "编辑后台",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val name = dlgBinding.etName.text.toString().trim()
                val url = dlgBinding.etUrl.text.toString().trim()
                val appId = dlgBinding.etAppId.text.toString().trim()
                val appSecret = dlgBinding.etAppSecret.text.toString().trim()
                if (name.isBlank() || url.isBlank() || appId.isBlank() || appSecret.isBlank()) {
                    Toast.makeText(this, "请完整填写名称、API 地址、AppID、AppSecret", Toast.LENGTH_SHORT).show()
                    return@showForm false
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    if (target != null) {
                        db.serverlessBackendDao().update(
                            target.copy(
                                name = name, baseUrl = url, appId = appId,
                                appSecret = appSecret, updatedAt = System.currentTimeMillis()
                            )
                        )
                    } else {
                        db.serverlessBackendDao().insert(
                            ServerlessBackend(
                                name = name, baseUrl = url, appId = appId,
                                appSecret = appSecret, sortOrder = backends.size
                            )
                        )
                    }
                    withContext(Dispatchers.Main) { loadBackends() }
                }
                true
            }
        )
    }

    private fun showBackendManage(backend: ServerlessBackend) {
        UnifiedDialogKit.showMenu(
            ctx = this,
            title = "后台「${backend.name}」",
            items = listOf("测试连接", "编辑", "删除")
        ) { which ->
            when (which) {
                0 -> testBackendConnection(backend)
                1 -> showBackendEdit(backend)
                2 -> showDestructiveConfirm(
                    this,
                    title = "删除后台",
                    message = "确定删除后台「${backend.name}」？仅删除本机配置，不影响后台服务。",
                    confirmText = "删除"
                ) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.serverlessBackendDao().delete(backend)
                        withContext(Dispatchers.Main) { loadBackends() }
                    }
                }
            }
        }
    }

    private fun testBackendConnection(backend: ServerlessBackend) {
        val config = ServerlessApiClient.normalize(backend)
        if (config == null) {
            Toast.makeText(this, "后台「${backend.name}」配置不完整，请先编辑补全", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            when (val res = ServerlessApiClient.fetchClients(config.first, config.second)) {
                is ServerlessApiClient.ApiResult.Ok -> {
                    val count = ServerlessApiClient.parseClients(res.json).size
                    Toast.makeText(this@ServerlessManagerActivity, "连接成功，当前在线客户端 $count 个", Toast.LENGTH_SHORT).show()
                }
                is ServerlessApiClient.ApiResult.Err ->
                    Toast.makeText(this@ServerlessManagerActivity, "API 请求失败：${res.msg}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBackend(backend: ServerlessBackend) {
        currentBackend = backend
        currentBackendId = backend.id
        binding.btnSwitchBackend.text = backend.name
        binding.rvClients.adapter = ClientAdapter(emptyList(), {}, {})
        showClientPage()
        loadClients()
    }

    // ══════════════ 客户端管理 ══════════════

    private fun showClientPage() {
        currentBackendId?.let { id ->
            backends.firstOrNull { it.id == id }?.let {
                binding.btnSwitchBackend.text = it.name
                binding.toolbar.title = "客户端管理"
                binding.toolbar.subtitle = it.name
            }
        }
        binding.backendPage.visibility = View.GONE
        binding.clientPage.visibility = View.VISIBLE
        binding.fabAddBackend.visibility = View.GONE
    }

    private fun showBackendPage() {
        currentBackendId = null
        currentBackend = null
        binding.backendPage.visibility = View.VISIBLE
        binding.clientPage.visibility = View.GONE
        binding.fabAddBackend.visibility = View.VISIBLE
        binding.toolbar.title = "客户端管理"
        binding.toolbar.subtitle = "EMQX Serverless 后台"
    }

    private fun showBackendChooser() {
        if (backends.isEmpty()) {
            Toast.makeText(this, "还没有配置后台", Toast.LENGTH_SHORT).show()
            return
        }
        if (backends.size <= 1) {
            Toast.makeText(this, "只有一个后台，无需切换", Toast.LENGTH_SHORT).show()
            return
        }
        UnifiedDialogKit.showSingleChoice(
            ctx = this,
            title = "选择后台",
            items = backends.map { it.name },
            selectedIndex = backends.indexOfFirst { it.id == currentBackendId }.coerceAtLeast(0)
        ) { index ->
            val chosen = backends[index]
            currentBackend = chosen
            currentBackendId = chosen.id
            binding.btnSwitchBackend.text = chosen.name
            loadClients()
        }
    }

    private fun loadClients() {
        val backend = currentBackend ?: return
        val config = ServerlessApiClient.normalize(backend)
        if (config == null) {
            Toast.makeText(this, "后台「${backend.name}」配置不完整，请先编辑补全", Toast.LENGTH_SHORT).show()
            return
        }
        val (baseUrl, auth) = config
        binding.btnRefresh.isEnabled = false
        binding.tvClientTitle.text = "正在查询…"
        lifecycleScope.launch {
            when (val res = ServerlessApiClient.fetchClients(baseUrl, auth)) {
                is ServerlessApiClient.ApiResult.Ok -> {
                    val list = ServerlessApiClient.parseClients(res.json)
                    binding.rvClients.adapter = ClientAdapter(
                        list,
                        onClick = { showClientDetail(baseUrl, auth, it) },
                        onKick = { confirmKick(baseUrl, auth, it) }
                    )
                    binding.emptyClient.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.tvClientTitle.text = "在线客户端（${list.size}）"
                }
                is ServerlessApiClient.ApiResult.Err -> {
                    binding.emptyClient.visibility = View.VISIBLE
                    binding.emptyClient.text = "请求失败：${res.msg}"
                    binding.tvClientTitle.text = "在线客户端"
                }
            }
            binding.btnRefresh.isEnabled = true
        }
    }

    private fun confirmKick(baseUrl: String, auth: String, client: ServerlessClient) {
        showDestructiveConfirm(
            this,
            title = "下线客户端",
            message = "确定下线客户端 ${client.clientId}？下线会终结其连接与会话。",
            confirmText = "下线"
        ) {
            lifecycleScope.launch {
                when (val res = ServerlessApiClient.kickClient(baseUrl, auth, client.clientId)) {
                    is ServerlessApiClient.ApiResult.Ok -> {
                        Toast.makeText(this@ServerlessManagerActivity, "已下线客户端 ${client.clientId}", Toast.LENGTH_SHORT).show()
                        loadClients()
                    }
                    is ServerlessApiClient.ApiResult.Err ->
                        Toast.makeText(this@ServerlessManagerActivity, "下线失败：${res.msg}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showClientDetail(baseUrl: String, auth: String, client: ServerlessClient) {
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addRow(k: String, v: String) {
            if (v.isBlank()) return
            val row = LinearLayout(this@ServerlessManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this@ServerlessManagerActivity).apply {
                text = k
                setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_onSurfaceVariant))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this@ServerlessManagerActivity).apply {
                text = v
                setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_onSurface))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            detail.addView(row)
        }
        addRow("客户端ID", client.clientId)
        addRow("用户名", client.username)
        addRow("地址", "${client.ip}${if (client.port.isNotBlank()) ":${client.port}" else ""}")
        addRow("协议", "MQTT v${client.protoVer}")
        addRow("保活间隔", "${client.keepalive}s")
        addRow("连接状态", if (client.connected) "在线" else "离线")
        addRow("连接时间", client.connectedAt)
        addRow("离线时间", client.disconnectedAt)
        addRow("订阅数", "${client.subscriptionsCnt}")
        addRow("收/发报文", "${client.recvPkt} / ${client.sendPkt}")
        addRow("收/发消息", "${client.recvMsg} / ${client.sendMsg}")
        val scroll = android.widget.ScrollView(this).apply { addView(detail) }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = scroll,
            title = "客户端详情",
            positiveText = "订阅管理",
            negativeText = "关闭",
            onConfirm = {
                showClientSubscriptions(baseUrl, auth, client.clientId)
                true
            }
        )
    }

    private fun showClientSubscriptions(baseUrl: String, auth: String, clientId: String) {
        val subContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusView = TextView(this).apply {
            text = "正在查询订阅…"
            setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_onSurfaceVariant))
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        subContainer.addView(statusView)
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        subContainer.addView(listHost)
        val topicEdit = EditText(this).apply {
            hint = "订阅主题，如 sensor/+/data"
            textSize = 13f
            maxLines = 1
        }
        subContainer.addView(
            topicEdit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )
        subContainer.addView(TextView(this).apply {
            text = "＋ 新增订阅"
            setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                val topic = topicEdit.text.toString().trim()
                if (topic.isBlank()) {
                    Toast.makeText(this@ServerlessManagerActivity, "请输入订阅主题", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    when (val res = ServerlessApiClient.subscribeClient(baseUrl, auth, clientId, topic, 0)) {
                        is ServerlessApiClient.ApiResult.Ok -> {
                            Toast.makeText(this@ServerlessManagerActivity, "已订阅 $topic", Toast.LENGTH_SHORT).show()
                            fetchSubscriptions(baseUrl, auth, clientId, statusView, listHost)
                        }
                        is ServerlessApiClient.ApiResult.Err ->
                            Toast.makeText(this@ServerlessManagerActivity, "订阅失败：${res.msg}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(subContainer) }
        UnifiedDialogKit.showForm(
            ctx = this,
            contentView = scroll,
            title = "订阅管理 · $clientId",
            positiveText = "刷新",
            negativeText = "关闭",
            onConfirm = {
                fetchSubscriptions(baseUrl, auth, clientId, statusView, listHost)
                false
            }
        )
        fetchSubscriptions(baseUrl, auth, clientId, statusView, listHost)
    }

    private fun fetchSubscriptions(
        baseUrl: String,
        auth: String,
        clientId: String,
        statusView: TextView,
        listHost: LinearLayout
    ) {
        lifecycleScope.launch {
            listHost.removeAllViews()
            when (val res = ServerlessApiClient.fetchSubscriptions(baseUrl, auth, clientId)) {
                is ServerlessApiClient.ApiResult.Ok -> {
                    val subs = ServerlessApiClient.parseSubscriptions(res.json)
                    statusView.text = "订阅主题（${subs.size}）"
                    if (subs.isEmpty()) {
                        listHost.addView(TextView(this@ServerlessManagerActivity).apply {
                            text = "该客户端暂无订阅"
                            setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_onSurfaceVariant))
                            textSize = 13f
                            setPadding(0, dp(4), 0, 0)
                        })
                        return@launch
                    }
                    subs.forEach { sub ->
                        val row = LinearLayout(this@ServerlessManagerActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(0, dp(5), 0, dp(5))
                        }
                        row.addView(TextView(this@ServerlessManagerActivity).apply {
                            text = "${sub.topic}（QoS${sub.qos}）"
                            setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_onSurface))
                            textSize = 13f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this@ServerlessManagerActivity).apply {
                            text = "退订"
                            setTextColor(ContextCompat.getColor(this@ServerlessManagerActivity, R.color.md_error))
                            textSize = 13f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setPadding(dp(16), 0, 0, 0)
                            setOnClickListener {
                                showDestructiveConfirm(
                                    this@ServerlessManagerActivity,
                                    title = "退订主题",
                                    message = "确定取消客户端对 ${sub.topic} 的订阅？",
                                    confirmText = "退订"
                                ) {
                                    lifecycleScope.launch {
                                        when (val r = ServerlessApiClient.unsubscribeClient(baseUrl, auth, clientId, sub.topic)) {
                                            is ServerlessApiClient.ApiResult.Ok ->
                                                fetchSubscriptions(baseUrl, auth, clientId, statusView, listHost)
                                            is ServerlessApiClient.ApiResult.Err ->
                                                Toast.makeText(this@ServerlessManagerActivity, "退订失败：${r.msg}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        })
                        listHost.addView(row)
                    }
                }
                is ServerlessApiClient.ApiResult.Err ->
                    statusView.text = "查询失败：${res.msg}"
            }
        }
    }

    private fun dp(value: Int): Int =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}
