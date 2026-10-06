package com.shangkele.core.jwgl

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/**
 * 用真实生成的 RSA 密钥对做往返验证，而不是断言一段写死的密文
 * ——密文每次都不一样（PKCS#1 v1.5 带随机填充）。
 */
class RsaPasswordEncryptorTest {

    private fun keyPair(bits: Int = 1024) =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    private fun unsigned(bytes: ByteArray): ByteArray =
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    private fun modulusBase64(key: java.security.KeyPair, padLeadingZero: Boolean): String {
        val raw = unsigned((key.public as RSAPublicKey).modulus.toByteArray())
        val bytes = if (padLeadingZero) ByteArray(1) + raw else raw
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun exponentBase64(key: java.security.KeyPair): String =
        Base64.getEncoder().encodeToString(unsigned((key.public as RSAPublicKey).publicExponent.toByteArray()))

    @Test
    fun `加密结果可被私钥还原`() {
        val key = keyPair()
        val password = "MjU2026#abc"

        val encrypted = RsaPasswordEncryptor.encrypt(
            password,
            modulusBase64(key, padLeadingZero = false),
            exponentBase64(key),
        )

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key.private)
        val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)

        assertEquals(password, decrypted)
    }

    @Test
    fun `modulus 带前导零字节时仍能加密`() {
        val key = keyPair()
        val password = "mju123456"

        val encrypted = RsaPasswordEncryptor.encrypt(
            password,
            modulusBase64(key, padLeadingZero = true),
            exponentBase64(key),
        )

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key.private)
        val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)

        assertEquals(password, decrypted)
    }

    @Test
    fun `指数 AQAB 就是 65537`() {
        val key = keyPair()
        assertEquals("AQAB", exponentBase64(key))
    }

    @Test
    fun `中文密码按 UTF-8 编码`() {
        val key = keyPair()
        val password = "上课啦2026"

        val encrypted = RsaPasswordEncryptor.encrypt(
            password,
            modulusBase64(key, padLeadingZero = false),
            exponentBase64(key),
        )

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key.private)
        assertEquals(password, String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8))
    }

    @Test
    fun `相同明文两次加密结果不同 填充是随机的`() {
        val key = keyPair()
        val modulus = modulusBase64(key, padLeadingZero = false)
        val exponent = exponentBase64(key)

        val first = RsaPasswordEncryptor.encrypt("same-password", modulus, exponent)
        val second = RsaPasswordEncryptor.encrypt("same-password", modulus, exponent)

        assert(first != second) { "PKCS#1 v1.5 应带随机填充，两次密文不应相同" }
    }
}
