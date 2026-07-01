package uz.ods.sso.oauth

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
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
    val rawSecret: String?,
)

@Service
class OAuthClientProvisioningService(
    private val clients: RegisteredClientRepository,
    private val jdbc: JdbcOperations,
    private val crypto: CryptoService,
    private val properties: OdsProperties,
) {
    fun createConfidential(
        tenantId: String,
        name: String,
        description: String?,
        redirectUris: List<String>,
    ): ProvisionedOAuthClient = create(
        tenantId = tenantId,
        name = name,
        description = description,
        redirectUris = redirectUris,
        postLogoutRedirectUris = emptyList(),
        scopes = DEFAULT_SCOPES,
        clientType = "confidential",
        tokenEndpointAuthMethod = "client_secret_basic",
        logoUri = null,
        hideOdsBranding = false,
    )

    fun create(
        tenantId: String,
        name: String,
        description: String?,
        redirectUris: List<String>,
        postLogoutRedirectUris: List<String>,
        scopes: List<String>,
        clientType: String,
        tokenEndpointAuthMethod: String,
        logoUri: String? = null,
        hideOdsBranding: Boolean = false,
    ): ProvisionedOAuthClient {
        validateName(name)
        validateRedirectUris(redirectUris)
        validateOptionalRedirectUris(postLogoutRedirectUris)
        logoUri?.trim()?.ifBlank { null }?.let { validateUriValues(listOf(it), "Logo URI") }
        val normalizedScopes = validateScopes(scopes)
        val publicClient = clientType == "public"
        if (clientType !in setOf("public", "confidential")) {
            throw validation("Client type must be public or confidential")
        }
        if (
            (!publicClient && tokenEndpointAuthMethod !in CONFIDENTIAL_AUTH_METHODS) ||
            (publicClient && tokenEndpointAuthMethod != "none")
        ) {
            throw validation("Token endpoint authentication method is not valid for this client type")
        }
        val rawSecret = crypto.randomUrl(36).takeUnless { publicClient }
        val builder = RegisteredClient.withId(newId("app"))
            .clientId(newId("cli"))
            .clientIdIssuedAt(Instant.now())
            .clientName(name.trim())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .setting("description", description.orEmpty().trim())
                    .setting("enabled", true)
                    .setting("tenant_id", tenantId)
                    .setting("logo_uri", logoUri?.trim().orEmpty())
                    .setting("hide_ods_branding", hideOdsBranding)
                    .build(),
            )
            .tokenSettings(defaultTokenSettings())
        redirectUris.distinct().forEach(builder::redirectUri)
        postLogoutRedirectUris.distinct().forEach(builder::postLogoutRedirectUri)
        normalizedScopes.forEach(builder::scope)
        if (publicClient) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        } else {
            builder.clientSecret("{argon2}${crypto.hashPassword(requireNotNull(rawSecret))}")
            builder.clientAuthenticationMethod(authenticationMethod(tokenEndpointAuthMethod))
        }
        val client = builder.build()
        clients.save(client)
        return ProvisionedOAuthClient(client, rawSecret)
    }

    fun update(
        existing: RegisteredClient,
        name: String?,
        description: String?,
        redirectUris: List<String>?,
        postLogoutRedirectUris: List<String>?,
        scopes: List<String>?,
        enabled: Boolean?,
        logoUri: String? = null,
        hideOdsBranding: Boolean? = null,
    ): RegisteredClient {
        name?.let(::validateName)
        redirectUris?.let(::validateRedirectUris)
        postLogoutRedirectUris?.let(::validateOptionalRedirectUris)
        logoUri?.trim()?.ifBlank { null }?.let { validateUriValues(listOf(it), "Logo URI") }
        val normalizedScopes = scopes?.let(::validateScopes)
        val builder = RegisteredClient.from(existing)
        name?.let { builder.clientName(it.trim()) }
        redirectUris?.let { values ->
            builder.redirectUris { current ->
                current.clear()
                current.addAll(values.distinct())
            }
        }
        postLogoutRedirectUris?.let { values ->
            builder.postLogoutRedirectUris { current ->
                current.clear()
                current.addAll(values.distinct())
            }
        }
        normalizedScopes?.let { values ->
            builder.scopes { current ->
                current.clear()
                current.addAll(values)
            }
        }
        if (description != null || enabled != null || logoUri != null || hideOdsBranding != null) {
            builder.clientSettings(
                ClientSettings.withSettings(existing.clientSettings.settings)
                    .setting(
                        "description",
                        description?.trim() ?: existing.clientSettings.settings["description"]?.toString().orEmpty(),
                    )
                    .setting("enabled", enabled ?: isEnabled(existing))
                    .setting(
                        "logo_uri",
                        logoUri?.trim()
                            ?: existing.clientSettings.settings["logo_uri"]?.toString().orEmpty(),
                    )
                    .setting(
                        "hide_ods_branding",
                        hideOdsBranding
                            ?: (existing.clientSettings.settings["hide_ods_branding"] as? Boolean ?: false),
                    )
                    .build(),
            )
        }
        return builder.build().also(clients::save)
    }

    fun rotateSecret(existing: RegisteredClient): ProvisionedOAuthClient {
        if (existing.clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE)) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "public_client_has_no_secret",
                "Public clients do not use a client secret",
            )
        }
        val rawSecret = crypto.randomUrl(36)
        val updated = RegisteredClient.from(existing)
            .clientSecret("{argon2}${crypto.hashPassword(rawSecret)}")
            .build()
        clients.save(updated)
        return ProvisionedOAuthClient(updated, rawSecret)
    }

    fun delete(existing: RegisteredClient) {
        jdbc.update("delete from oauth2_authorization where registered_client_id = ?", existing.id)
        jdbc.update("delete from oauth2_authorization_consent where registered_client_id = ?", existing.id)
        jdbc.update("delete from user_consents where client_id = ?", existing.clientId)
        jdbc.update("delete from used_refresh_tokens where client_id = ?", existing.clientId)
        jdbc.update("delete from oauth2_registered_client where id = ?", existing.id)
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
            throw validation("One to ten redirect URIs are required")
        }
        validateUriValues(values, "Redirect URI")
    }

    private fun validateOptionalRedirectUris(values: List<String>) {
        if (values.size > 10) {
            throw validation("No more than ten post-logout redirect URIs are allowed")
        }
        validateUriValues(values, "Post-logout redirect URI")
    }

    private fun validateUriValues(values: List<String>, label: String) {
        values.forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            val local = uri?.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
            val secure = uri?.scheme == "https" && !uri.host.isNullOrBlank()
            if (uri == null || uri.fragment != null || (!secure && !local)) {
                throw validation("$label must use HTTPS; HTTP is allowed only for localhost")
            }
        }
    }

    private fun validateScopes(values: List<String>): List<String> {
        val normalized = values.map(String::trim).filter(String::isNotEmpty).distinct()
        if (OidcScopes.OPENID !in normalized) {
            throw validation("The openid scope is required")
        }
        if (normalized.any { it !in ALLOWED_SCOPES }) {
            throw validation("Unsupported scope requested")
        }
        return normalized
    }

    private fun authenticationMethod(value: String) = when (value) {
        "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST
        else -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC
    }

    private fun validation(message: String) =
        AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", message)

    companion object {
        val DEFAULT_SCOPES = listOf(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL, OdsOidcScopes.PICTURE)
        val ALLOWED_SCOPES = setOf(
            OidcScopes.OPENID,
            OidcScopes.PROFILE,
            OidcScopes.EMAIL,
            OidcScopes.PHONE,
            OdsOidcScopes.FULL_NAME_CYRILLIC,
            OdsOidcScopes.FULL_NAME_LATIN,
            OdsOidcScopes.PICTURE,
            "offline_access",
        )
        val CONFIDENTIAL_AUTH_METHODS = setOf("client_secret_basic", "client_secret_post")
    }
}
