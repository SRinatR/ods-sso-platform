package uz.ods.sso.oauth

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.AppException
import uz.ods.sso.shared.newId
import uz.ods.sso.tenant.TenantAwareRegisteredClientRepository
import java.net.URI
import java.time.Duration
import java.time.Instant

data class ProvisionedOAuthClient(
    val client: RegisteredClient,
    val rawSecret: String,
)

@Service
class OAuthClientProvisioningService(
    private val clients: RegisteredClientRepository,
    private val crypto: CryptoService,
    private val properties: OdsProperties,
) {
    fun createConfidential(
        tenantId: String,
        name: String,
        description: String?,
        redirectUris: List<String>,
    ): ProvisionedOAuthClient {
        validateName(name)
        validateRedirectUris(redirectUris)
        val rawSecret = crypto.randomUrl(36)
        val client = RegisteredClient.withId(newId("app"))
            .clientId(newId("cli"))
            .clientIdIssuedAt(Instant.now())
            .clientSecret("{argon2}${crypto.hashPassword(rawSecret)}")
            .clientName(name.trim())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .scope(OidcScopes.EMAIL)
            .scope("offline_access")
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .setting("description", description.orEmpty().trim())
                    .setting("enabled", true)
                    .setting("tenant_id", tenantId)
                    .build(),
            )
            .tokenSettings(defaultTokenSettings())
            .apply { redirectUris.distinct().forEach(::redirectUri) }
            .build()
        clients.save(client)
        return ProvisionedOAuthClient(client, rawSecret)
    }

    fun update(
        existing: RegisteredClient,
        name: String?,
        description: String?,
        redirectUris: List<String>?,
        enabled: Boolean?,
    ): RegisteredClient {
        name?.let(::validateName)
        redirectUris?.let(::validateRedirectUris)
        val builder = RegisteredClient.from(existing)
        name?.let { builder.clientName(it.trim()) }
        redirectUris?.let { values ->
            builder.redirectUris { current ->
                current.clear()
                current.addAll(values.distinct())
            }
        }
        if (description != null || enabled != null) {
            builder.clientSettings(
                ClientSettings.withSettings(existing.clientSettings.settings)
                    .setting(
                        "description",
                        description?.trim() ?: existing.clientSettings.settings["description"]?.toString().orEmpty(),
                    )
                    .setting("enabled", enabled ?: isEnabled(existing))
                    .build(),
            )
        }
        return builder.build().also(clients::save)
    }

    fun rotateSecret(existing: RegisteredClient): ProvisionedOAuthClient {
        val rawSecret = crypto.randomUrl(36)
        val updated = RegisteredClient.from(existing)
            .clientSecret("{argon2}${crypto.hashPassword(rawSecret)}")
            .build()
        clients.save(updated)
        return ProvisionedOAuthClient(updated, rawSecret)
    }

    fun findIncludingDisabledById(id: String): RegisteredClient? =
        (clients as? TenantAwareRegisteredClientRepository)?.findByIdIncludingDisabled(id)
            ?: clients.findById(id)

    fun isEnabled(client: RegisteredClient): Boolean =
        client.clientSettings.settings["enabled"] as? Boolean ?: true

    private fun defaultTokenSettings(): TokenSettings = TokenSettings.builder()
        .accessTokenTimeToLive(Duration.ofSeconds(properties.accessTokenTtl))
        .refreshTokenTimeToLive(Duration.ofSeconds(properties.refreshTokenTtl))
        .reuseRefreshTokens(false)
        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
        .build()

    private fun validateName(value: String) {
        if (value.trim().length !in 2..160) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_error",
                "Application name must contain 2 to 160 characters",
            )
        }
    }

    private fun validateRedirectUris(values: List<String>) {
        if (values.isEmpty() || values.size > 10) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_error",
                "One to ten redirect URIs are required",
            )
        }
        values.forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            val local = uri?.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
            val secure = uri?.scheme == "https" && !uri.host.isNullOrBlank()
            if (uri == null || uri.fragment != null || (!secure && !local)) {
                throw AppException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "validation_error",
                    "Redirect URI must use HTTPS; HTTP is allowed only for localhost",
                )
            }
        }
    }
}
