package com.wangfanlab.whale.transport

/**
 * 队列 payload 加解密接口（SEC-01）。
 *
 * 生产实现由 [DeviceIdentity] 的 Keystore AES-256/GCM 主密钥支撑
 * （[DeviceIdentity.encryptString]/[DeviceIdentity.decryptString]，材料不出 Keystore）；
 * 测试可注入任意实现。
 */
interface QueueCipher {
    fun encrypt(plain: String): String

    /** 解密失败（密文损坏/密钥失效）必须抛异常，不得返回部分内容。 */
    fun decrypt(blob: String): String
}

/** 以设备身份主密钥加解密的默认实现。 */
class IdentityQueueCipher(private val identity: DeviceIdentity) : QueueCipher {
    override fun encrypt(plain: String): String = identity.encryptString(plain)
    override fun decrypt(blob: String): String = identity.decryptString(blob)
}
