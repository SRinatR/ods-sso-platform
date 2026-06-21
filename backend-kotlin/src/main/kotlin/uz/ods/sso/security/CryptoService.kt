package uz.ods.sso.security

import org.springframework.http.HttpStatus
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.shared.AppException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class CryptoService(
    private val properties: OdsProperties,
) {
    private val random = SecureRandom()
    private val passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun hashPassword(password: String): String {
        if (password.length !in 12..128) {
            throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_password", "Password must contain 12 to 128 characters")
        }
        return requireNotNull(passwordEncoder.encode(password))
    }

    fun matchesPassword(password: String, encoded: String): Boolean =
        password.length <= 128 && runCatching { passwordEncoder.matches(password, encoded) }.getOrDefault(false)

    fun hashSecret(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.tokenPepper.toByteArray(), "HmacSHA256"))
        return mac.doFinal(secret.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun secretMatches(secret: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hashSecret(secret).toByteArray(), expectedHash.toByteArray())

    fun opaqueToken(prefix: String): Triple<String, String, String> {
        val id = "${prefix}_${randomUrl(18)}"
        val secret = randomUrl(32)
        return Triple(id, secret, "$id.$secret")
    }

    fun splitToken(raw: String, prefix: String): Pair<String, String> {
        val parts = raw.split(".", limit = 2)
        if (parts.size != 2 || !parts[0].startsWith("${prefix}_") || parts[1].isBlank()) {
            throw AppException(HttpStatus.BAD_REQUEST, "invalid_token", "Token format is invalid")
        }
        return parts[0] to parts[1]
    }

    fun encrypt(value: String, context: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray())
        val encrypted = cipher.doFinal(value.toByteArray())
        return urlEncoder.encodeToString(nonce + encrypted)
    }

    fun decrypt(value: String, context: String): String {
        val payload = Base64.getUrlDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray())
        return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(StandardCharsets.UTF_8)
    }

    fun newTotpSecret(): String = Base32.encode(ByteArray(20).also(random::nextBytes))

    fun verifyTotp(secret: String, code: String, now: Instant = Instant.now()): Boolean {
        if (!code.matches(Regex("^\\d{6}$"))) return false
        val counter = now.epochSecond / 30
        return (-1L..1L).any { offset -> totp(secret, counter + offset) == code }
    }

    fun provisioningUri(secret: String, email: String): String {
        val label = URLEncoder.encode("ODS Identity:$email", StandardCharsets.UTF_8)
        return "otpauth://totp/$label?secret=$secret&issuer=ODS+Identity&algorithm=SHA1&digits=6&period=30"
    }

    fun fingerprint(userAgent: String?, ipAddress: String): String {
        val network = ipAddress.split(".").take(3).joinToString(".")
        return sha256("${userAgent.orEmpty()}|$network")
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun randomUrl(size: Int): String = urlEncoder.encodeToString(ByteArray(size).also(random::nextBytes))

    private fun totp(secret: String, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(Base32.decode(secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun encryptionKey(): SecretKeySpec {
        val keyBytes = if (properties.encryptionKey.isNotBlank()) {
            Base64.getUrlDecoder().decode(properties.encryptionKey)
        } else {
            MessageDigest.getInstance("SHA-256").digest(properties.sessionSecret.toByteArray())
        }
        require(keyBytes.size == 32) { "TOTP_ENCRYPTION_KEY must decode to exactly 32 bytes" }
        return SecretKeySpec(keyBytes, "AES")
    }
}

private object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                result.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 31])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) result.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 31])
        return result.toString()
    }

    fun decode(value: String): ByteArray {
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        value.uppercase().filterNot { it == '=' }.forEach { char ->
            val index = ALPHABET.indexOf(char)
            require(index >= 0) { "Invalid Base32 value" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output += ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
    }
}
