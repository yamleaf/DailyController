package com.yample.daily.controller

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256（RFC 5869），与控制端派生会话密钥逻辑一致（ikm/salt/info 必须相同） */
object Hkdf {
    private const val ALG = "HmacSHA256"

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) { 0 } else salt, ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, n)
            offset += n
            counter++
        }
        return okm
    }

    fun deriveHex(ikm: String, salt: String, info: String, length: Int): String =
        derive(ikm.toByteArray(), salt.toByteArray(), info.toByteArray(), length)
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(key, ALG))
        return mac.doFinal(data)
    }
}
