package uz.ods.sso.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import uz.ods.sso.config.OdsProperties
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CryptoServiceTest {
    private val service = CryptoService(
        OdsProperties(
            sessionSecret = "session-secret-that-is-longer-than-32-characters",
            tokenPepper = "token-pepper-that-is-independent-and-long",
        ),
    )

    @Test
    fun `hashes and verifies passwords with Argon2id`() {
        val hash = service.hashPassword("correct horse battery staple")

        assertThat(hash).startsWith("\$argon2")
        assertThat(service.matchesPassword("correct horse battery staple", hash)).isTrue()
        assertThat(service.matchesPassword("wrong password", hash)).isFalse()
    }

    @Test
    fun `rejects passwords outside contract`() {
        assertThatThrownBy { service.hashPassword("too-short") }
            .isInstanceOf(uz.ods.sso.shared.AppException::class.java)
            .hasMessageContaining("12 to 128")
    }

    @Test
    fun `opaque tokens never store raw secrets`() {
        val (id, secret, raw) = service.opaqueToken("ses")
        val parsed = service.splitToken(raw, "ses")
        val hash = service.hashSecret(secret)

        assertThat(parsed).isEqualTo(id to secret)
        assertThat(hash).doesNotContain(secret)
        assertThat(service.secretMatches(secret, hash)).isTrue()
        assertThat(service.secretMatches("tampered", hash)).isFalse()
    }

    @Test
    fun `AES GCM binds ciphertext to context`() {
        val encrypted = service.encrypt("highly-sensitive", "totp:user-1")

        assertThat(service.decrypt(encrypted, "totp:user-1")).isEqualTo("highly-sensitive")
        assertThatThrownBy { service.decrypt(encrypted, "totp:user-2") }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `verifies TOTP in accepted time window`() {
        val secret = service.newTotpSecret()
        val now = Instant.parse("2026-06-20T12:00:00Z")
        val code = totp(secret, now.epochSecond / 30)

        assertThat(service.verifyTotp(secret, code, now)).isTrue()
        assertThat(service.verifyTotp(secret, "000000", now)).isFalse()
    }

    @Test
    fun `device fingerprint is stable within network prefix`() {
        val first = service.fingerprint("Browser/1", "192.168.10.20")
        val second = service.fingerprint("Browser/1", "192.168.10.99")

        assertThat(first).isEqualTo(second)
        assertThat(service.fingerprint("Browser/2", "192.168.10.20")).isNotEqualTo(first)
    }

    private fun totp(secret: String, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        value.forEach {
            buffer = (buffer shl 5) or alphabet.indexOf(it)
            bits += 5
            if (bits >= 8) {
                result += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return result.toByteArray()
    }
}
