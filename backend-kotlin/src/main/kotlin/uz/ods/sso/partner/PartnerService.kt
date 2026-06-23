package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
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
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

@Service
class PartnerService(
    private val sessions: SessionService,
    private val organizations: PartnerOrganizationRepository,
    private val memberships: PartnerMembershipRepository,
    private val applications: PartnerApplicationRepository,
    private val users: UserRepository,
    private val oauthClients: OAuthClientProvisioningService,
    private val audit: AuditService,
    private val properties: OdsProperties,
    private val domains: PartnerDomainService,
) {
    fun workspace(request: HttpServletRequest): PartnerWorkspaceResponse {
        val principal = sessions.current()
        val available = activeOrganizations(principal)
        val requestedSlug = domains.requestedSlug(request)
        val selected = requestedSlug?.let { slug ->
            available.firstOrNull { it.first.slug == slug }
                ?: throw AppException(
                    HttpStatus.NOT_FOUND,
                    "partner_workspace_not_found",
                    "Partner workspace was not found for this domain",
                )
        }
        val organization = selected?.first
        val membership = selected?.second
        return PartnerWorkspaceResponse(
            organization = organization?.toResponse(membership!!.role),
            applications = organization?.let {
                applications.findByOrganizationIdOrderByCreatedAtDesc(it.id).mapNotNull(::applicationResponse)
            }.orEmpty(),
            integration = integrationMetadata(),
            organizations = available.map { (item, itemMembership) -> item.toResponse(itemMembership.role) },
            members = if (organization != null) membersFor(organization) else emptyList(),
        )
    }

    @Transactional
    fun createOrganization(body: PartnerOrganizationCreate, request: HttpServletRequest): PartnerWorkspaceResponse {
        val principal = sessions.current()
        val websiteUrl = domains.normalizeWebsite(body.websiteUrl)
        val slug = domains.requireAvailableSlug(
            body.slug?.trim()?.ifBlank { null }
                ?: domains.deriveSlug(websiteUrl)
                ?: throw AppException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "partner_slug_required",
                    "Organization code is required when it cannot be derived from the website",
                ),
        )
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
        val membership = memberships.save(
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
        val available = activeOrganizations(principal)
        return PartnerWorkspaceResponse(
            organization = organization.toResponse(membership.role),
            applications = emptyList(),
            integration = integrationMetadata(),
            organizations = available.map { (item, itemMembership) -> item.toResponse(itemMembership.role) },
            members = membersFor(organization),
        )
    }

    @Transactional
    fun createApplication(body: PartnerApplicationCreate, request: HttpServletRequest): PartnerApplicationResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal, request)
        requireManager(membership)
        val provisioned = oauthClients.create(
            tenantId = principal.user.tenantId,
            name = body.name,
            description = body.description,
            redirectUris = body.redirectUris,
            postLogoutRedirectUris = body.postLogoutRedirectUris,
            scopes = body.scopes,
            clientType = body.clientType,
            tokenEndpointAuthMethod = body.tokenEndpointAuthMethod,
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
        val (organization, membership) = requireOrganization(principal, request)
        requireManager(membership)
        val metadata = applications.findByPublicIdAndOrganizationId(applicationId, organization.id)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val existing = oauthClients.findIncludingDisabledById(metadata.registeredClientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_application_not_found", "Application was not found")
        val existingType =
            if (existing.clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE)) "public" else "confidential"
        val existingAuthMethod = existing.clientAuthenticationMethods.firstOrNull()?.value ?: "none"
        if (body.clientType != null && body.clientType != existingType) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "client_type_immutable",
                "Client type cannot be changed after registration",
            )
        }
        if (body.tokenEndpointAuthMethod != null && body.tokenEndpointAuthMethod != existingAuthMethod) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "client_auth_method_immutable",
                "Token endpoint authentication method cannot be changed after registration",
            )
        }
        val updated = oauthClients.update(
            existing,
            body.name,
            body.description,
            body.redirectUris,
            body.postLogoutRedirectUris,
            body.scopes,
            body.enabled,
        )
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
        val (organization, membership) = requireOrganization(principal, request)
        requireManager(membership)
        val metadata = applications.findByPublicIdAndOrganizationId(applicationId, organization.id)
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

    @Transactional
    fun createMember(body: PartnerMemberCreate, request: HttpServletRequest): PartnerMemberResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal, request)
        requireOwner(membership)
        val user = users.findByTenantIdAndEmailIgnoreCase(
            principal.user.tenantId,
            body.email.trim().lowercase(),
        ) ?: throw AppException(
            HttpStatus.NOT_FOUND,
            "partner_user_not_found",
            "The user must register an ODS account before being added to the organization",
        )
        if (memberships.findByOrganizationIdAndUserId(organization.id, user.id) != null) {
            throw AppException(
                HttpStatus.CONFLICT,
                "partner_membership_exists",
                "This user already belongs to the organization",
            )
        }
        val created = memberships.save(
            PartnerMembershipEntity(
                organizationId = organization.id,
                userId = user.id,
                role = body.role,
            ),
        )
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_MEMBER_ADDED",
            principal.user.id,
            user.id,
            details = mapOf("organization_id" to organization.id, "role" to body.role),
        )
        return created.toResponse(user)
    }

    @Transactional
    fun updateMember(
        membershipId: String,
        body: PartnerMemberUpdate,
        request: HttpServletRequest,
    ): PartnerMemberResponse {
        val principal = sessions.current()
        val (organization, currentMembership) = requireOrganization(principal, request)
        requireOwner(currentMembership)
        val membership = memberships.findByPublicIdAndOrganizationId(membershipId, organization.id)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_member_not_found", "Organization member was not found")
        if (membership.role == "owner") {
            throw AppException(
                HttpStatus.CONFLICT,
                "partner_owner_immutable",
                "The primary organization administrator cannot be changed",
            )
        }
        body.role?.let { membership.role = it }
        body.status?.let { membership.status = it }
        val user = users.findByPublicId(membership.userId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_member_not_found", "Organization member was not found")
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_MEMBER_UPDATED",
            principal.user.id,
            user.id,
            details = mapOf(
                "organization_id" to organization.id,
                "role" to membership.role,
                "status" to membership.status,
            ),
        )
        return membership.toResponse(user)
    }

    private fun activeOrganizations(
        principal: CurrentPrincipal,
    ): List<Pair<PartnerOrganizationEntity, PartnerMembershipEntity>> =
        memberships.findByUserIdAndStatusOrderByCreatedAtAsc(principal.user.id, "active")
            .mapNotNull { membership ->
                organizations.findByPublicId(membership.organizationId)
                    ?.takeIf { it.tenantId == principal.user.tenantId && it.status == "active" }
                    ?.let { it to membership }
            }

    private fun requireOrganization(
        principal: CurrentPrincipal,
        request: HttpServletRequest,
    ): Pair<PartnerOrganizationEntity, PartnerMembershipEntity> {
        val requestedSlug = domains.requestedSlug(request) ?: throw AppException(
            HttpStatus.PRECONDITION_REQUIRED,
            "partner_domain_required",
            "Open the organization portal URL before changing organization settings",
        )
        return activeOrganizations(principal).firstOrNull { it.first.slug == requestedSlug }
            ?: throw AppException(
                HttpStatus.NOT_FOUND,
                "partner_workspace_not_found",
                "Partner workspace was not found for this domain",
            )
    }

    private fun requireManager(membership: PartnerMembershipEntity) {
        if (membership.role !in setOf("owner", "admin")) {
            throw AppException(HttpStatus.FORBIDDEN, "partner_admin_required", "Partner administrator access is required")
        }
    }

    private fun requireOwner(membership: PartnerMembershipEntity) {
        if (membership.role != "owner") {
            throw AppException(
                HttpStatus.FORBIDDEN,
                "partner_owner_required",
                "Primary organization administrator access is required",
            )
        }
    }

    private fun membersFor(organization: PartnerOrganizationEntity): List<PartnerMemberResponse> =
        memberships.findByOrganizationIdOrderByCreatedAtAsc(organization.id)
            .mapNotNull { membership ->
                users.findByPublicId(membership.userId)?.let { user -> membership.toResponse(user) }
            }

    private fun PartnerMembershipEntity.toResponse(user: UserEntity) = PartnerMemberResponse(
        id = id,
        userId = user.id,
        email = user.email,
        name = user.name,
        role = role,
        status = status,
        createdAt = createdAt,
    )

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
        postLogoutRedirectUris = postLogoutRedirectUris.sorted(),
        scopes = scopes.sorted(),
        clientType = if (clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE)) "public" else "confidential",
        tokenEndpointAuthMethod = clientAuthenticationMethods.firstOrNull()?.value ?: "none",
        requirePkce = clientSettings.isRequireProofKey,
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
        portalUrl = domains.portalUrl(slug),
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
            endSessionEndpoint = "$issuer/connect/logout",
            supportedScopes = OAuthClientProvisioningService.ALLOWED_SCOPES.sorted(),
            supportedClientTypes = listOf("confidential", "public"),
            supportedTokenEndpointAuthMethods = listOf("client_secret_basic", "client_secret_post", "none"),
        )
    }

}
