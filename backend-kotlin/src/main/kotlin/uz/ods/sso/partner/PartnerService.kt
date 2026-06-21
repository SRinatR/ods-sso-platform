package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.oauth.OAuthClientProvisioningService
import uz.ods.sso.persistence.PartnerApplicationEntity
import uz.ods.sso.persistence.PartnerApplicationRepository
import uz.ods.sso.persistence.PartnerMembershipEntity
import uz.ods.sso.persistence.PartnerMembershipRepository
import uz.ods.sso.persistence.PartnerOrganizationEntity
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.net.URI
import java.time.Instant

@Service
class PartnerService(
    private val sessions: SessionService,
    private val organizations: PartnerOrganizationRepository,
    private val memberships: PartnerMembershipRepository,
    private val applications: PartnerApplicationRepository,
    private val oauthClients: OAuthClientProvisioningService,
    private val audit: AuditService,
    private val properties: OdsProperties,
) {
    fun workspace(): PartnerWorkspaceResponse {
        val principal = sessions.current()
        val membership = activeMembership(principal)
        val organization = membership?.let { organizations.findById(it.organizationId).orElse(null) }
        val appResponses = if (organization == null) {
            emptyList()
        } else {
            applications.findByOrganizationIdOrderByCreatedAtDesc(organization.id).mapNotNull(::applicationResponse)
        }
        return PartnerWorkspaceResponse(
            organization = if (organization != null) {
                organization.toResponse(membership.role)
            } else {
                null
            },
            applications = appResponses,
            integration = integrationMetadata(),
        )
    }

    @Transactional
    fun createOrganization(body: PartnerOrganizationCreate, request: HttpServletRequest): PartnerWorkspaceResponse {
        val principal = sessions.current()
        if (activeMembership(principal) != null) {
            throw AppException(
                HttpStatus.CONFLICT,
                "partner_organization_exists",
                "Your account already belongs to a partner organization",
            )
        }
        val slug = body.slug.trim().lowercase()
        if (organizations.findByTenantIdAndSlug(principal.user.tenantId, slug) != null) {
            throw AppException(HttpStatus.CONFLICT, "partner_slug_taken", "This organization code is already in use")
        }
        val websiteUrl = body.websiteUrl?.trim()?.ifBlank { null }
        validateWebsite(websiteUrl)
        val organization = organizations.save(
            PartnerOrganizationEntity(
                tenantId = principal.user.tenantId,
                slug = slug,
                name = body.name.trim(),
                legalName = body.legalName?.trim()?.ifBlank { null },
                websiteUrl = websiteUrl,
                contactEmail = body.contactEmail.trim().lowercase(),
            ),
        )
        memberships.save(
            PartnerMembershipEntity(
                organizationId = organization.id,
                userId = principal.user.id,
                role = "owner",
            ),
        )
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_ORGANIZATION_CREATED",
            principal.user.id,
            organization.id,
            details = mapOf("slug" to organization.slug),
        )
        return workspace()
    }

    @Transactional
    fun createApplication(body: PartnerApplicationCreate, request: HttpServletRequest): PartnerApplicationResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal)
        requireManager(membership)
        val provisioned = oauthClients.createConfidential(
            tenantId = principal.user.tenantId,
            name = body.name,
            description = body.description,
            redirectUris = body.redirectUris,
        )
        val metadata = applications.save(
            PartnerApplicationEntity(
                organizationId = organization.id,
                registeredClientId = provisioned.client.id,
                clientId = provisioned.client.clientId,
                createdBy = principal.user.id,
            ),
        )
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_APPLICATION_CREATED",
            principal.user.id,
            metadata.id,
            provisioned.client.clientId,
            mapOf("organization_id" to organization.id),
        )
        return provisioned.client.toResponse(metadata, provisioned.rawSecret)
    }

    @Transactional
    fun updateApplication(
        applicationId: String,
        body: PartnerApplicationUpdate,
        request: HttpServletRequest,
    ): PartnerApplicationResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal)
        requireManager(membership)
        val metadata = applications.findByIdAndOrganizationId(applicationId, organization.id)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val existing = oauthClients.findIncludingDisabledById(metadata.registeredClientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val updated = oauthClients.update(existing, body.name, body.description, body.redirectUris, body.enabled)
        metadata.updatedAt = Instant.now()
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_APPLICATION_UPDATED",
            principal.user.id,
            metadata.id,
            updated.clientId,
        )
        return updated.toResponse(metadata)
    }

    @Transactional
    fun rotateSecret(applicationId: String, request: HttpServletRequest): PartnerApplicationResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal)
        requireManager(membership)
        val metadata = applications.findByIdAndOrganizationId(applicationId, organization.id)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val existing = oauthClients.findIncludingDisabledById(metadata.registeredClientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val rotated = oauthClients.rotateSecret(existing)
        metadata.updatedAt = Instant.now()
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_APPLICATION_SECRET_ROTATED",
            principal.user.id,
            metadata.id,
            rotated.client.clientId,
        )
        return rotated.client.toResponse(metadata, rotated.rawSecret)
    }

    private fun activeMembership(principal: CurrentPrincipal): PartnerMembershipEntity? =
        memberships.findFirstByUserIdAndStatusOrderByCreatedAtAsc(principal.user.id, "active")

    private fun requireOrganization(
        principal: CurrentPrincipal,
    ): Pair<PartnerOrganizationEntity, PartnerMembershipEntity> {
        val membership = activeMembership(principal)
            ?: throw AppException(
                HttpStatus.PRECONDITION_REQUIRED,
                "partner_organization_required",
                "Create a partner organization first",
            )
        val organization = organizations.findById(membership.organizationId).orElseThrow {
            AppException(HttpStatus.NOT_FOUND, "partner_organization_not_found", "Partner organization was not found")
        }
        if (organization.tenantId != principal.user.tenantId || organization.status != "active") {
            throw AppException(HttpStatus.NOT_FOUND, "partner_organization_not_found", "Partner organization was not found")
        }
        return organization to membership
    }

    private fun requireManager(membership: PartnerMembershipEntity) {
        if (membership.role !in setOf("owner", "admin")) {
            throw AppException(HttpStatus.FORBIDDEN, "partner_admin_required", "Partner administrator access is required")
        }
    }

    private fun applicationResponse(metadata: PartnerApplicationEntity): PartnerApplicationResponse? =
        oauthClients.findIncludingDisabledById(metadata.registeredClientId)?.toResponse(metadata)

    private fun RegisteredClient.toResponse(
        metadata: PartnerApplicationEntity,
        rawSecret: String? = null,
    ) = PartnerApplicationResponse(
        id = metadata.id,
        clientId = clientId,
        clientSecret = rawSecret,
        name = clientName,
        description = clientSettings.settings["description"]?.toString()?.ifBlank { null },
        redirectUris = redirectUris.sorted(),
        scopes = scopes.sorted(),
        tokenEndpointAuthMethods = clientAuthenticationMethods.map { it.value }.sorted(),
        enabled = oauthClients.isEnabled(this),
        createdAt = metadata.createdAt,
    )

    private fun PartnerOrganizationEntity.toResponse(role: String) = PartnerOrganizationResponse(
        id = id,
        name = name,
        slug = slug,
        legalName = legalName,
        websiteUrl = websiteUrl,
        contactEmail = contactEmail,
        status = status,
        role = role,
        createdAt = createdAt,
    )

    private fun integrationMetadata(): PartnerIntegrationMetadata {
        val issuer = properties.issuer.trimEnd('/')
        return PartnerIntegrationMetadata(
            issuer = issuer,
            discoveryUrl = "$issuer/.well-known/openid-configuration",
            authorizationEndpoint = "$issuer/authorize",
            tokenEndpoint = "$issuer/token",
            userInfoEndpoint = "$issuer/userinfo",
            jwksUrl = "$issuer/.well-known/jwks.json",
        )
    }

    private fun validateWebsite(value: String?) {
        if (value == null) return
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Website URL is invalid")
        }
    }
}
