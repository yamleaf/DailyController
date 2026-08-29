package com.yample.daily.controller

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * 控制端数据备份/恢复（导出/导入）。
 *
 * 设计要点：
 * - 目标场景是「更换签名密钥 → 卸载重装 → 重新导入」，因此加密必须能跨全新安装解密，
 *   不能依赖 Android Keystore（卸载即清空）。故使用「用户口令 → PBKDF2 → AES-256-GCM」，
 *   口令由用户在导出时设定、导入时再次输入，标准 JCE 实现，无需新增第三方依赖。
 * - 数据块：Room 两个表（devices / serverless_backends）+ 一组 SharedPreferences 文件
 *   （remote_ctrl 告警历史与远程开关、daily_app 离线通知、mqtt_quota 用量、daily_theme 主题）。
 * - 文件格式：magic + formatVersion + 迭代次数 + salt + iv + AES-GCM 密文(含 tag)。
 */
object BackupManager {

    private const val MAGIC = "DCBK1"
    private const val FORMAT_VERSION = 1
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** 参与备份的 SharedPreferences 文件（名称与各模块保持一致） */
    private val PREF_FILES = listOf("remote_ctrl", "daily_app", "mqtt_quota", "daily_theme")

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val random = SecureRandom()

    /** 单个偏好项：显式记录类型以便恢复时按正确类型写回 */
    private data class PrefEntry(val k: String, val t: String, val v: Any)

    private data class BackupEnvelope(
        val version: Int,
        val createdAt: Long,
        /** 导出本备份的源设备 ANDROID_ID，用于导入时判断是否同一台手机 */
        val deviceId: String = "",
        val devices: List<DeviceRecord>? = null,
        val serverlessBackends: List<ServerlessBackend>? = null,
        val prefs: Map<String, List<PrefEntry>> = emptyMap()
    )

    /**
     * 本机稳定标识（混淆化设备凭证）：基于系统稳定值 ANDROID_ID（卸载重装同机不变、换机变化），
     * 加固定应用盐做 SHA-256 哈希，避免在备份内容中暴露 ANDROID_ID 明文，防简单伪造/肉眼复制。
     * 同样的设备 -> 相同哈希，故同机重装后导入仍能匹配为同一台设备。
     */
    private const val DEVICE_SALT = "dailycontroller.backup.device.v1"

    private fun currentDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$DEVICE_SALT:$androidId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildDb(context: Context): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "daily-db")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    // ===================== 导出 =====================

    /** 收集当前数据为明文 JSON；[passphrase] 为空则跳过加密（仅调试用，正式入口必传） */
    suspend fun export(context: Context, passphrase: String): ByteArray {
        val db = buildDb(context)
        val devices = runCatching { db.deviceDao().getAll() }.getOrDefault(emptyList())
        val backends = runCatching { db.serverlessBackendDao().getAll() }.getOrDefault(emptyList())
        val prefs = mutableMapOf<String, List<PrefEntry>>()
        PREF_FILES.forEach { file ->
            val sp = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            prefs[file] = dumpPrefs(sp)
        }
        val envelope = BackupEnvelope(
            version = FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            deviceId = currentDeviceId(context),
            devices = devices,
            serverlessBackends = backends,
            prefs = prefs
        )
        return encrypt(gson.toJson(envelope), passphrase)
    }

    /** 把导出字节流写入输出流（SAF 已打开的 uri） */
    fun writeTo(binary: ByteArray, out: OutputStream) {
        out.use { it.write(binary); it.flush() }
    }

    private fun dumpPrefs(sp: SharedPreferences): List<PrefEntry> {
        val out = mutableListOf<PrefEntry>()
        sp.all.forEach { (k, v) ->
            when (v) {
                is String -> out += PrefEntry(k, "string", v)
                is Boolean -> out += PrefEntry(k, "boolean", v)
                is Long -> out += PrefEntry(k, "long", v)
                is Int -> out += PrefEntry(k, "int", v)
                is Float -> out += PrefEntry(k, "float", v)
                is Set<*> -> out += PrefEntry(k, "stringset", v.filter { it is String })
                else -> Unit
            }
        }
        return out
    }

    // ===================== 导入 =====================

    /** 导入结果：恢复的设备数 + 是否需要重新配对 */
    data class ImportResult(
        val restored: Int,
        /** true 表示此备份来自另一台手机，设备已还原基础信息但需重新扫码配对 */
        val needRepair: Boolean
    )

    /**
     * 校验并恢复备份（覆盖式）。
     * - 本机导入（deviceId 一致）：完整还原设备，保留会话凭证，无需重新配对。
     * - 换机导入（deviceId 不一致）：还原设备基础信息，但清空 bound/sessionSecret/pairingToken，
     *   需重新扫码配对，避免旧手机活凭证被新手机窃用。
     * @return 恢复结果；解析/口令错误抛 [IllegalArgumentException]。
     */
    suspend fun import(context: Context, binary: ByteArray, passphrase: String): ImportResult {
        val json = decrypt(binary, passphrase)
        val envelope = try {
            gson.fromJson(json, BackupEnvelope::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件已损坏", e)
        }
        if (envelope.version != FORMAT_VERSION) {
            throw IllegalArgumentException("备份版本不受支持：v${envelope.version}")
        }

        val db = buildDb(context)
        val sameDevice = envelope.deviceId == currentDeviceId(context)

        // 1) 覆盖 devices（换机时剥离配对凭证）
        val rawDevices = envelope.devices ?: emptyList()
        val devices = if (sameDevice) rawDevices else rawDevices.map { sanitizeDevice(it) }
        db.deviceDao().clearAll()
        devices.forEach { db.deviceDao().insert(it) }

        // 2) 覆盖 serverless_backends
        val backends = envelope.serverlessBackends ?: emptyList()
        db.serverlessBackendDao().clearAll()
        backends.forEach { db.serverlessBackendDao().insert(it) }

        // 3) 覆盖各 prefs 文件（整文件重建，避免残留旧键）
        PREF_FILES.forEach { file ->
            val sp = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            restorePrefs(sp, envelope.prefs[file].orEmpty())
        }

        return ImportResult(restored = devices.size, needRepair = !sameDevice)
    }

    /** 换机导入时剥离设备上的配对与会话凭证，保留可展示的基础信息 */
    private fun sanitizeDevice(d: DeviceRecord): DeviceRecord = d.copy(
        ctlPass = "",
        sessionSecret = "",
        pairingToken = "",
        bound = false
    )

    private fun restorePrefs(sp: SharedPreferences, entries: List<PrefEntry>) {
        val ed = sp.edit().clear()
        entries.forEach { e ->
            val num = e.v as? Number
            when (e.t) {
                "string" -> ed.putString(e.k, e.v as? String ?: "")
                "boolean" -> ed.putBoolean(e.k, e.v as? Boolean ?: false)
                "long" -> ed.putLong(e.k, num?.toLong() ?: 0L)
                "int" -> ed.putInt(e.k, num?.toInt() ?: 0)
                "float" -> ed.putFloat(e.k, num?.toFloat() ?: 0f)
                "stringset" -> @Suppress("UNCHECKED_CAST") ed.putStringSet(e.k, (e.v as? List<String>)?.toSet())
            }
        }
        ed.apply()
    }

    // ===================== 加密原语 =====================

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec: KeySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec)
    }

    private fun encrypt(plaintext: String, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(passphrase.toCharArray(), salt)
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        val size = magicBytes.size + 4 + 4 + salt.size + iv.size + body.size
        val out = java.io.ByteArrayOutputStream(size)
        out.write(magicBytes)
        writeInt(out, FORMAT_VERSION)
        writeInt(out, PBKDF2_ITERATIONS)
        out.write(salt)
        out.write(iv)
        out.write(body)
        return out.toByteArray()
    }

    private fun decrypt(binary: ByteArray, passphrase: String): String {
        val data = ByteArrayInputStream(binary)
        val magic = ByteArray(MAGIC.length).also { data.read(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw IllegalArgumentException("不是有效的遥控备份文件")
        val version = readInt(data)
        val iterations = readInt(data)
        if (version != FORMAT_VERSION) throw IllegalArgumentException("备份版本不受支持：v$version")
        if (iterations <= 0) throw IllegalArgumentException("备份文件已损坏")

        val salt = ByteArray(SALT_LEN).also { data.read(it) }
        val iv = ByteArray(IV_LEN).also { data.read(it) }
        val body = data.readBytes()
        val key = deriveKey(passphrase.toCharArray(), salt)
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("口令错误或文件已损坏", e)
        }
    }

    private fun writeInt(out: java.io.OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readInt(inp: InputStream): Int {
        val b = ByteArray(4).also { inp.read(it) }
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }
}