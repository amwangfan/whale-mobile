package com.wangfanlab.whale.transport

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备身份（contracts/v1 §3）：Ed25519 密钥对 + device_id = SHA-256(公钥) 前 16 字节小写 hex。
 *
 * Android Keystore 对 Ed25519 支持不全，故采用：
 *  1. Keystore 生成 AES-256/GCM 主密钥（优先 StrongBox，不可用时降级软件 TEE/普通；
 *     密钥 material 不出 Keystore）；
 *  2. Ed25519 种子由 SecureRandom 生成（32 字节），经主密钥 GCM 加密后存 SharedPreferences；
 *  3. 签名走 BouncyCastle 轻量级 API（Ed25519Signer），不注册 JCA Provider，
 *     避免与 Android 内置裁剪版 BouncyCastle 冲突。
 *
 * 注意：主密钥失效（如恢复出厂/解锁方式变更）时身份会重新生成，device_id 改变，
 * 需要重新配对；此时会打 WARNING 日志。
 */
class DeviceIdentity(context: Context) {

    private val appContext = context.applicationContext
    private val publicKeyBytes: ByteArray
    private val privateParams: Ed25519PrivateKeyParameters

    init {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SEED_BLOB, null)
        val seed: ByteArray = if (stored != null) {
            try {
                decryptBytes(Base64.decode(stored, Base64.NO_WRAP))
            } catch (e: Exception) {
                Log.w(TAG, "Ed25519 种子解密失败（主密钥可能已失效），重新生成身份", e)
                generateAndStore(prefs)
            }
        } else {
            generateAndStore(prefs)
        }
        privateParams = Ed25519PrivateKeyParameters(seed, 0)
        publicKeyBytes = privateParams.generatePublicKey().encoded
        seed.fill(0) // 种子仅存于加密后 blob，内存中尽快清零
    }

    /** Ed25519 公钥（32 字节）的小写 hex，64 字符。用于配对登记（dev_gateway pair-request --pubkey）。 */
    fun getPublicKeyHex(): String = publicKeyBytes.toHex()

    /** §3：device_id = SHA-256(公钥) 前 16 字节小写 hex（32 字符）。 */
    fun getDeviceId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.copyOfRange(0, 16).toHex()
    }

    /** Ed25519 签名（64 字节）。对 challenge 签名时传入其 UTF-8 字节（见 auth.challenge schema）。 */
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun signHex(data: ByteArray): String = sign(data).toHex()

    /**
     * 以身份主密钥（Keystore AES-256/GCM）加密任意短文本（如 ScreenMemo MCP Bearer token），
     * 输出 Base64(iv||ciphertext||tag)。与设备种子共用同一主密钥，材料不出 Keystore。
     */
    fun encryptString(plaintext: String): String =
        Base64.encodeToString(encryptBytes(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    /** [encryptString] 的逆操作；blob 损坏或主密钥失效（换锁/恢复出厂）时抛异常。 */
    fun decryptString(blobBase64: String): String =
        String(decryptBytes(Base64.decode(blobBase64, Base64.NO_WRAP)), Charsets.UTF_8)

    // ---- 内部：Keystore 主密钥与种子加解密 ----

    private fun generateAndStore(prefs: android.content.SharedPreferences): ByteArray {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SEED_BLOB, Base64.encodeToString(encryptBytes(seed), Base64.NO_WRAP)).apply()
        return seed
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        fun spec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey().also { Log.i(TAG, "主密钥已生成（StrongBox）") }
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "设备不支持 StrongBox，主密钥降级为普通 Keystore")
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    /** blob = iv(12B) || ciphertext || GCM tag(16B)。IV 每次随机。 */
    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv + ciphertext
    }

    private fun decryptBytes(blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_LENGTH) { "blob too short" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateMasterKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, GCM_IV_LENGTH)),
        )
        return cipher.doFinal(blob.copyOfRange(GCM_IV_LENGTH, blob.size))
    }

    companion object {
        private const val TAG = "WhaleIdentity"
        private const val PREFS_NAME = "whale_identity"
        private const val KEY_SEED_BLOB = "ed25519_seed_enc_v1"
        private const val MASTER_KEY_ALIAS = "whale_identity_master_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/** 校验辅助：device_id 与公钥 hex 的一致性（§3）。仅测试/调试使用。 */
fun ed25519DeviceIdOf(publicKey: Ed25519PublicKeyParameters): String =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).copyOfRange(0, 16)
        .joinToString("") { "%02x".format(it) }
