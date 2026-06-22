package uz.ods.sso.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.SecurityPolicyEntity
import uz.ods.sso.persistence.SecurityPolicyRepository
import uz.ods.sso.persistence.BackupCodeRepository
import uz.ods.sso.persistence.MfaMethodRepository
import uz.ods.sso.persistence.TenantEntity
import uz.ods.sso.persistence.TenantRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.newId
import java.time.Duration
import java.time.Instant

@Component
class BootstrapService(
    private val properties: OdsProperties,
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val policies: SecurityPolicyRepository,
    private val mfaMethods: MfaMethodRepository,
    private val backupCodes: BackupCodeRepository,
    private val sessions: UserSessionRepository,
    private val clients: RegisteredClientRepository,
    private val crypto: CryptoService,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        validateRuntime()
        val tenant = tenants.findBySlug(properties.defaultTenant) ?: tenants.save(
            TenantEntity(slug = properties.defaultTenant, name = "ODS Platform"),
        )
        seedPolicies(tenant.id)
        seedAdmin(tenant.id)
        seedTatarlarClient(tenant.id)
    }

    private fun seedPolicies(tenantId: String) {
        defaultPolicies.forEach { (key, value) ->
            if (policies.findByTenantIdAndKey(tenantId, key) == null) {
                policies.save(
                    SecurityPolicyEntity(
                        tenantId = tenantId,
                        key = key,
                        valueJson = objectMapper.writeValueAsString(value),
                    ),
                )
            }
        }
    }

    private fun seedAdmin(tenantId: String) {
        if (properties.bootstrapAdminEmail.isBlank() || properties.bootstrapAdminPassword.isBlank()) return
        val email = properties.bootstrapAdminEmail.lowercase()
        val existing = users.findByTenantIdAndEmailIgnoreCase(tenantId, email)
        if (existing == null) {
            users.save(
                UserEntity(
                    tenantId = tenantId,
                    email = email,
                    passwordHash = crypto.hashBootstrapPassword(properties.bootstrapAdminPassword),
                    name = "Platform Administrator",
                    role = "admin",
                    status = "active",
                    emailVerifiedAt = Instant.now(),
                    termsAcceptedAt = Instant.now(),
                ),
            )
            log.info("bootstrap_admin_created email_hash={}", crypto.sha256(email))
            return
        }
        if (!properties.bootstrapAdminReconcile) return

        val fingerprint = crypto.hashSecret("bootstrap-admin:$email:${properties.bootstrapAdminPassword}")
        val markerValue = objectMapper.writeValueAsString(mapOf("fingerprint" to fingerprint))
        val markerKey = "bootstrap_admin_reconcile"
        val marker = policies.findByTenantIdAndKey(tenantId, markerKey)
        if (marker?.valueJson == markerValue) return

        existing.passwordHash = crypto.hashBootstrapPassword(properties.bootstrapAdminPassword)
        existing.role = "admin"
        existing.status = "active"
        existing.emailVerifiedAt = existing.emailVerifiedAt ?: Instant.now()
        existing.termsAcceptedAt = existing.termsAcceptedAt ?: Instant.now()
        existing.mfaEnabled = false
        existing.failedLoginCount = 0
        existing.lockedUntil = null
        existing.updatedAt = Instant.now()
        users.save(existing)
        mfaMethods.deleteByUserId(existing.id)
        backupCodes.deleteByUserId(existing.id)
        sessions.revokeAll(existing.id, Instant.now())

        policies.save(
            marker?.apply {
                valueJson = markerValue
                updatedBy = existing.id
                updatedAt = Instant.now()
            } ?: SecurityPolicyEntity(
                tenantId = tenantId,
                key = markerKey,
                valueJson = markerValue,
                updatedBy = existing.id,
            ),
        )
        log.warn("bootstrap_admin_reconciled email_hash={}", crypto.sha256(email))
    }

    private fun seedTatarlarClient(tenantId: String) {
        if (properties.tatarlarClientSecret.isBlank()) return
        val clientId = "ods_tatarlar_staging"
        if (clients.findByClientId(clientId) != null) return
        clients.save(
            RegisteredClient.withId(newId("app"))
                .clientId(clientId)
                .clientIdIssuedAt(Instant.now())
                .clientSecret("{argon2}${crypto.hashPassword(properties.tatarlarClientSecret)}")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://api-staging.tatarlar.uz/api/v1/auth/sso/callback")
                .redirectUri("http://localhost:3002/api/v1/auth/sso/callback")
                .redirectUri("https://api.tatarlar.uz/api/v1/auth/sso/callback")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .clientName("Tatarlar Platform")
                .clientSettings(
                    ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .setting("description", "Staging and production OIDC integration")
                        .setting("enabled", true)
                        .setting("tenant_id", tenantId)
                        .build(),
                )
                .tokenSettings(
                    TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(properties.accessTokenTtl))
                        .refreshTokenTimeToLive(Duration.ofSeconds(properties.refreshTokenTtl))
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .build(),
                )
                .build(),
        )
    }

    private fun validateRuntime() {
        require(properties.sessionSecret.length >= 32) { "SESSION_SECRET must contain at least 32 characters" }
        require(properties.tokenPepper.length >= 32) { "TOKEN_PEPPER must contain at least 32 characters" }
        require(properties.sessionSecret != properties.tokenPepper) { "SESSION_SECRET and TOKEN_PEPPER must be independent" }
        if (!properties.productionLike) return
        require(properties.requireEmailVerification) { "REQUIRE_EMAIL_VERIFICATION must be true in production" }
        require("change-before-production" !in properties.sessionSecret) { "Development SESSION_SECRET cannot be used" }
        require("change-before-production" !in properties.tokenPepper) { "Development TOKEN_PEPPER cannot be used" }
        require(properties.encryptionKey.isNotBlank()) { "TOTP_ENCRYPTION_KEY is required" }
        require(properties.jwtPrivateKey.isNotBlank() && properties.jwtPublicKey.isNotBlank()) {
            "JWT_PRIVATE_KEY and JWT_PUBLIC_KEY are required"
        }
        listOf("ISSUER" to properties.issuer, "ACCOUNT_URL" to properties.accountUrl, "API_URL" to properties.apiUrl)
            .forEach { (name, value) -> require(value.startsWith("https://")) { "$name must use HTTPS" } }
    }

    companion object {
        val defaultPolicies = mapOf(
            "password" to mapOf("minimum_length" to 12, "maximum_length" to 128, "argon2id" to true),
            "login_protection" to mapOf("maximum_failed_attempts" to 5, "lock_seconds" to 900),
            "admin" to mapOf("mfa_required" to true, "step_up_seconds" to 600),
            "oauth" to mapOf(
                "pkce_method" to "S256",
                "access_token_seconds" to 900,
                "refresh_rotation" to true,
                "reuse_detection" to true,
            ),
            "risk" to mapOf("new_device_score" to 25, "step_up_threshold" to 40, "deny_threshold" to 80),
        )
    }
}
