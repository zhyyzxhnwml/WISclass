package com.shangkele.core.jwgl

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 正方教务的密码加密，对应前端 `login.js`：
 *
 * ```js
 * rsaKey.setPublic(b64tohex(modulus), b64tohex(exponent));
 * var enPassword = hex2b64(rsaKey.encrypt(password));
 * ```
 *
 * 即 1024 位 RSA + PKCS#1 v1.5 填充，输出 Base64。
 *
 * 三个已知坑（docs/03-教务系统对接.md §三 Step 3）：
 *  1. Base64 解出来的 modulus 可能是 129 字节（带前导零），必须用 `BigInteger(1, bytes)` 无视符号位
 *  2. 加密结果里的 `+ / =` 在表单编码时必须转义（由调用方负责）
 *  3. 提交 URL 必须带 `?time=<毫秒时间戳>`（由调用方负责）
 *
 * 只用 java.util.Base64（API 26+），因此可以在 JVM 单测里用真实密钥验证。
 */
object RsaPasswordEncryptor {

    /**
     * @param modulusBase64  `/jwglxt/xtgl/login_getPublicKey.html` 返回的 `modulus`
     * @param exponentBase64 同上返回的 `exponent`，通常是 `AQAB`
     * @return Base64 编码的密文
     */
    fun encrypt(password: String, modulusBase64: String, exponentBase64: String): String {
        val modulus = BigInteger(1, Base64.getDecoder().decode(modulusBase64.trim()))
        val exponent = BigInteger(1, Base64.getDecoder().decode(exponentBase64.trim()))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }
}
