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
    @field:Size(max = 1000)
    val logoUri: String? = null,
    val hideOdsBranding: Boolean = false,
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
    @field:Size(max = 1000)
    val logoUri: String? = null,
    val hideOdsBranding: Boolean? = null,
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
    val logoUri: String?,
    val hideOdsBranding: Boolean,
    val createdAt: Instant,
)

data class PartnerMemberCreate(
    @field:Email
    val email: String,
    @field:Pattern(regexp = "^(editor|user|viewer)$")
    val role: String,
)

data class PartnerMemberUpdate(
    @field:Pattern(regexp = "^(editor|user|viewer)$")
    val role: String? = null,
    @field:Pattern(regexp = "^(active|disabled)$")
    val status: String? = null,
)

data class PartnerMemberResponse(
    val id: String,
    val userId: String,
    val email: String,
    val name: String?,
    val role: String,
    val status: String,
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

data class PartnerAnalyticsResponse(
    val windowDays: Int,
    val generatedAt: Instant,
    val summary: PartnerAnalyticsSummary,
    val applications: List<PartnerApplicationAnalytics>,
    val recentEvents: List<PartnerAnalyticsEvent>,
)

data class PartnerAnalyticsSummary(
    val successfulSsoLogins: Long,
    val tokenRefreshes: Long,
    val uniqueUsers: Long,
    val consentsGranted: Long,
    val consentsRevoked: Long,
    val securityFailures: Long,
    val configurationChanges: Long,
)

data class PartnerApplicationAnalytics(
    val clientId: String,
    val name: String,
    val successfulSsoLogins: Long,
    val tokenRefreshes: Long,
    val uniqueUsers: Long,
    val consentsGranted: Long,
    val consentsRevoked: Long,
    val securityFailures: Long,
    val configurationChanges: Long,
)

data class PartnerAnalyticsEvent(
    val id: String,
    val eventType: String,
    val clientId: String?,
    val applicationName: String?,
    val requestId: String,
    val createdAt: Instant,
)

data class PartnerWorkspaceResponse(
    val organization: PartnerOrganizationResponse?,
    val applications: List<PartnerApplicationResponse>,
    val integration: PartnerIntegrationMetadata,
    val organizations: List<PartnerOrganizationResponse> = emptyList(),
    val members: List<PartnerMemberResponse> = emptyList(),
)
