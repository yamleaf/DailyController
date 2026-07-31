package com.yample.daily.controller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import com.google.gson.Gson
import com.google.zxing.integration.android.IntentIntegrator
import com.yample.daily.controller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .fallbackToDestructiveMigration()
            .build()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)

        binding.fabAdd.setOnClickListener { startScan() }
        binding.btnEmptyScan.setOnClickListener { startScan() }
        loadDevices()
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

    private fun loadDevices() {
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = db.deviceDao().getAll()
            withContext(Dispatchers.Main) {
                val adapter = DeviceAdapter(devices) { device ->
                    val intent = Intent(this@MainActivity, DeviceControlActivity::class.java)
                    intent.putExtra("device", device)
                    startActivity(intent)
                }
                binding.rvDevices.adapter = adapter
                binding.toolbarMain.subtitle = "共 ${devices.size} 台设备"
                binding.emptyState.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDevices.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
            }
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

    private fun addDevice(payload: BindingPayload) {
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
                Toast.makeText(this@MainActivity, "设备添加成功，请在设备控制页完成配对", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
