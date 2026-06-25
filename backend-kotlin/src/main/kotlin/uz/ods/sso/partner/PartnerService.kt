package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.oauth.OAuthClientProvisioningService
import uz.ods.sso.persistence.AuditLogEntity
import uz.ods.sso.persistence.AuditLogRepository
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
    private val auditLogs: AuditLogRepository,
    private val properties: OdsProperties,
    private val domains: PartnerDomainService,
) {
    fun workspace(request: HttpServletRequest): PartnerWorkspaceResponse {
        val principal = sessions.current()
        val available = activeOrganizations(principal)
        val requestedSlug = domains.requestedSlug(request)
        val selected = requestedSlug?.let { slug ->
            available.firstOrNull { it.first.slug == slug }
                ?: throwWorkspaceAccessError(slug)
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

    fun analytics(request: HttpServletRequest): PartnerAnalyticsResponse {
        val principal = sessions.current()
        val (organization, membership) = requireOrganization(principal, request)
        requireManager(membership)

        val generatedAt = Instant.now()
        val since = generatedAt.minusSeconds(ANALYTICS_WINDOW_DAYS * 24L * 60L * 60L)
        val appMetadata = applications.findByOrganizationIdOrderByCreatedAtDesc(organization.id)
        val clientIds = appMetadata.map { it.clientId }
        val clientNames = appMetadata.associate { it.clientId to (applicationResponse(it)?.name ?: it.clientId) }

        val eventCountsByClient = if (clientIds.isEmpty()) emptyMap() else {
            auditLogs.countClientEventsByType(
                principal.user.tenantId,
                clientIds,
                ANALYTICS_CLIENT_EVENTS,
                since,
            ).groupBy { it.clientId.orEmpty() }
                .mapValues { (_, values) -> values.associate { it.eventType to it.total } }
        }
        val uniqueUsersByClient = if (clientIds.isEmpty()) emptyMap() else {
            auditLogs.countDistinctClientActorsByClient(
                principal.user.tenantId,
                clientIds,
                setOf("OAUTH_TOKEN_ISSUED"),
                since,
            ).associate { it.clientId.orEmpty() to it.total }
        }
        val configurationChanges = auditLogs.countOrganizationEvents(
            principal.user.tenantId,
            organization.id,
            ANALYTICS_CONFIGURATION_EVENTS,
            since,
        )
        val applicationAnalytics = appMetadata.map { metadata ->
            val counts = eventCountsByClient[metadata.clientId].orEmpty()
            PartnerApplicationAnalytics(
                clientId = metadata.clientId,
                name = clientNames[metadata.clientId] ?: metadata.clientId,
                successfulSsoLogins = counts.totalOf("OAUTH_TOKEN_ISSUED"),
                tokenRefreshes = counts.totalOf("OAUTH_TOKEN_REFRESHED"),
                uniqueUsers = uniqueUsersByClient[metadata.clientId] ?: 0,
                consentsGranted = counts.totalOf("CONSENT_GRANTED"),
                consentsRevoked = counts.totalOf("CONSENT_REVOKED"),
                securityFailures = counts.totalOf("REFRESH_TOKEN_REUSE_DETECTED"),
                configurationChanges = counts.totalOfConfigurationEvents(),
            )
        }
        val oauthEvents = if (clientIds.isEmpty()) emptyList() else {
            auditLogs.findClientEvents(
                principal.user.tenantId,
                clientIds,
                ANALYTICS_CLIENT_EVENTS,
                since,
                PageRequest.of(0, 20),
            )
        }
        val managementEvents = auditLogs.findOrganizationEvents(
            principal.user.tenantId,
            organization.id,
            ANALYTICS_CONFIGURATION_EVENTS,
            since,
            PageRequest.of(0, 20),
        )
        return PartnerAnalyticsResponse(
            windowDays = ANALYTICS_WINDOW_DAYS,
            generatedAt = generatedAt,
            summary = PartnerAnalyticsSummary(
                successfulSsoLogins = applicationAnalytics.sumOf { it.successfulSsoLogins },
                tokenRefreshes = applicationAnalytics.sumOf { it.tokenRefreshes },
                uniqueUsers = if (clientIds.isEmpty()) 0 else {
                    auditLogs.countDistinctClientActors(
                        principal.user.tenantId,
                        clientIds,
                        setOf("OAUTH_TOKEN_ISSUED"),
                        since,
                    )
                },
                consentsGranted = applicationAnalytics.sumOf { it.consentsGranted },
                consentsRevoked = applicationAnalytics.sumOf { it.consentsRevoked },
                securityFailures = applicationAnalytics.sumOf { it.securityFailures },
                configurationChanges = configurationChanges,
            ),
            applications = applicationAnalytics,
            recentEvents = (oauthEvents + managementEvents)
                .sortedByDescending(AuditLogEntity::createdAt)
                .take(20)
                .map { event ->
                    PartnerAnalyticsEvent(
                        id = event.id,
                        eventType = event.eventType,
                        clientId = event.clientId,
                        applicationName = event.clientId?.let(clientNames::get),
                        requestId = event.requestId,
                        createdAt = event.createdAt,
                    )
                },
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
            logoUri = body.logoUri,
            hideOdsBranding = body.hideOdsBranding,
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
            body.logoUri,
            body.hideOdsBranding,
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
            ?: throwWorkspaceAccessError(requestedSlug)
    }

    private fun throwWorkspaceAccessError(slug: String): Nothing {
        val organizationExists = organizations.findBySlugAndStatus(slug, "active") != null
        if (organizationExists) {
            throw AppException(
                HttpStatus.FORBIDDEN,
                "partner_workspace_forbidden",
                "Your ODS account is not a member of this organization",
            )
        }
        throw AppException(
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
        logoUri = clientSettings.settings["logo_uri"]?.toString()?.ifBlank { null },
        hideOdsBranding = clientSettings.settings["hide_ods_branding"] as? Boolean ?: false,
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

    private fun Map<String, Long>.totalOf(eventType: String): Long = this[eventType] ?: 0

    private fun Map<String, Long>.totalOfConfigurationEvents(): Long =
        ANALYTICS_CONFIGURATION_EVENTS.sumOf { this[it] ?: 0 }

    companion object {
        private const val ANALYTICS_WINDOW_DAYS = 30

        private val ANALYTICS_CLIENT_EVENTS = setOf(
            "OAUTH_TOKEN_ISSUED",
            "OAUTH_TOKEN_REFRESHED",
            "CONSENT_GRANTED",
            "CONSENT_REVOKED",
            "REFRESH_TOKEN_REUSE_DETECTED",
            "PARTNER_APPLICATION_CREATED",
            "PARTNER_APPLICATION_UPDATED",
            "PARTNER_APPLICATION_SECRET_ROTATED",
        )

        private val ANALYTICS_CONFIGURATION_EVENTS = setOf(
            "PARTNER_ORGANIZATION_CREATED",
            "PARTNER_APPLICATION_CREATED",
            "PARTNER_APPLICATION_UPDATED",
            "PARTNER_APPLICATION_SECRET_ROTATED",
            "PARTNER_MEMBER_ADDED",
            "PARTNER_MEMBER_UPDATED",
        )
    }
}
