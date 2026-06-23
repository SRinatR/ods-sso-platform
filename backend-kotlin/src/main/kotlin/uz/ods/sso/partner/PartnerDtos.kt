package uz.ods.sso.partner

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

data class PartnerOrganizationCreate(
    @field:NotBlank
    @field:Size(min = 2, max = 160)
    val name: String,
    @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,62}$")
    val slug: String? = null,
    @field:Size(max = 255)
    val legalName: String? = null,
    @field:Size(max = 512)
    val websiteUrl: String? = null,
    @field:Email
    val contactEmail: String,
)

data class PartnerOrganizationResponse(
    val id: String,
    val name: String,
    val slug: String,
    val legalName: String?,
    val websiteUrl: String?,
    val contactEmail: String,
    val status: String,
    val role: String,
    val portalUrl: String,
    val createdAt: Instant,
)

data class PartnerApplicationCreate(
    @field:NotBlank
    @field:Size(min = 2, max = 160)
    val name: String,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Size(min = 1, max = 10)
    val redirectUris: List<String>,
    @field:Size(max = 10)
    val postLogoutRedirectUris: List<String> = emptyList(),
    @field:Size(min = 1, max = 5)
    val scopes: List<String> = listOf("openid", "profile", "email"),
    @field:Pattern(regexp = "^(public|confidential)$")
    val clientType: String = "confidential",
    @field:Pattern(regexp = "^(none|client_secret_basic|client_secret_post)$")
    val tokenEndpointAuthMethod: String = "client_secret_basic",
)

data class PartnerApplicationUpdate(
    @field:Size(min = 2, max = 160)
    val name: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Size(min = 1, max = 10)
    val redirectUris: List<String>? = null,
    @field:Size(max = 10)
    val postLogoutRedirectUris: List<String>? = null,
    @field:Size(min = 1, max = 5)
    val scopes: List<String>? = null,
    @field:Pattern(regexp = "^(public|confidential)$")
    val clientType: String? = null,
    @field:Pattern(regexp = "^(none|client_secret_basic|client_secret_post)$")
    val tokenEndpointAuthMethod: String? = null,
    val enabled: Boolean? = null,
)

data class PartnerApplicationResponse(
    val id: String,
    val clientId: String,
    val clientSecret: String? = null,
    val name: String,
    val description: String?,
    val redirectUris: List<String>,
    val postLogoutRedirectUris: List<String>,
    val scopes: List<String>,
    val clientType: String,
    val tokenEndpointAuthMethod: String,
    val requirePkce: Boolean,
    val enabled: Boolean,
    val createdAt: Instant,
)

data class PartnerIntegrationMetadata(
    val issuer: String,
    val discoveryUrl: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userInfoEndpoint: String,
    val jwksUrl: String,
    val endSessionEndpoint: String,
    val supportedScopes: List<String>,
    val supportedClientTypes: List<String>,
    val supportedTokenEndpointAuthMethods: List<String>,
)

data class PartnerWorkspaceResponse(
    val organization: PartnerOrganizationResponse?,
    val applications: List<PartnerApplicationResponse>,
    val integration: PartnerIntegrationMetadata,
)
