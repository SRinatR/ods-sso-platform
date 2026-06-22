package uz.ods.sso.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("ods")
data class OdsProperties(
    val environment: String = "dev",
    val issuer: String = "http://localhost:8080",
    val accountUrl: String = "http://account.localhost",
    val apiUrl: String = "http://localhost:8080",
    val rootDomain: String = "localhost",
    val allowedOrigins: String = "http://account.localhost,http://localhost:3000",
    val allowedOriginPatterns: String = "",
    val sessionCookieDomain: String = "",
    val mailFrom: String = "ODS Identity <no-reply@ods.uz>",
    val mailReplyTo: String = "support@ods.uz",
    val sessionSecret: String,
    val tokenPepper: String,
    val encryptionKey: String = "",
    val jwtPrivateKey: String = "",
    val jwtPublicKey: String = "",
    val jwtKeyId: String = "ods-platform-1",
    val sessionTtl: Long = Duration.ofDays(30).seconds,
    val verificationTokenTtl: Long = Duration.ofDays(1).seconds,
    val passwordResetTokenTtl: Long = Duration.ofHours(1).seconds,
    val preauthTokenTtl: Long = Duration.ofMinutes(5).seconds,
    val stepUpTtl: Long = Duration.ofMinutes(10).seconds,
    val accessTokenTtl: Long = Duration.ofMinutes(15).seconds,
    val refreshTokenTtl: Long = Duration.ofDays(30).seconds,
    val requireEmailVerification: Boolean = true,
    val bootstrapAdminEmail: String = "",
    val bootstrapAdminPassword: String = "",
    val bootstrapAdminReconcile: Boolean = false,
    val kafkaEventsEnabled: Boolean = false,
    val defaultTenant: String = "default",
    val trustedProxyCount: Int = 0,
) {
    val productionLike: Boolean get() = environment in setOf("staging", "production")
    val corsOrigins: List<String>
        get() = (allowedOrigins.split(",") + accountUrl).map(String::trim).filter(String::isNotEmpty).distinct()
    val corsOriginPatterns: List<String>
        get() = allowedOriginPatterns.split(",").map(String::trim).filter(String::isNotEmpty).distinct()

    fun partnerPortalUrl(slug: String): String = "https://$slug.$rootDomain"
}
