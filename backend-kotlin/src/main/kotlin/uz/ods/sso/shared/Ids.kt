package uz.ods.sso.shared

import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()
private val encoder = Base64.getUrlEncoder().withoutPadding()

fun newId(prefix: String): String {
    val bytes = ByteArray(18)
    secureRandom.nextBytes(bytes)
    return "${prefix}_${encoder.encodeToString(bytes)}"
}
