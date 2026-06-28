package uz.ods.sso.admin

import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.PageRequest
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
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.identity.UserResponse
import uz.ods.sso.identity.toResponse
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.oauth.OdsOidcScopes
import uz.ods.sso.oauth.OAuthClientProvisioningService
import uz.ods.sso.persistence.AuditLogRepository
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.PartnerApplicationEntity
import uz.ods.sso.persistence.PartnerApplicationRepository
import uz.ods.sso.persistence.PartnerOrganizationEntity
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.persistence.SecurityPolicyEntity
import uz.ods.sso.persistence.SecurityPolicyRepository
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.RateLimiter
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import uz.ods.sso.shared.newId
import uz.ods.sso.tenant.TenantAwareRegisteredClientRepository
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class AdminDashboardResponse(
    val usersTotal: Long,
    val usersActive: Long,
    val activeSessions: Long,
    val oauthClients: Long,
    val failedLogins24h: Long,
    val auditEvents24h: Long,
)

data class AdminUserUpdate(val status: String? = null, val role: String? = null)

data class OAuthClientCreate(
    val name: String,
    val description: String? = null,
    val redirectUris: List<String>,
    val allowedScopes: List<String> = listOf("openid", "profile", "email"),
    val isPublic: Boolean = false,
    val tokenEndpointAuthMethod: String = "client_secret_basic",
)

data class OAuthClientUpdate(
    val name: String? = null,
    val description: String? = null,
    val redirectUris: List<String>? = null,
    val allowedScopes: List<String>? = null,
    val enabled: Boolean? = null,
)

data class OAuthClientResponse(
    val id: String,
    val clientId: String,
    val name: String,
    val description: String?,
    val redirectUris: List<String>,
    val allowedScopes: List<String>,
    val grantTypes: List<String>,
    val tokenEndpointAuthMethod: String,
    val isPublic: Boolean,
    val requirePkce: Boolean,
    val enabled: Boolean,
    val createdAt: Instant,
    val clientSecret: String? = null,
)

data class AdminOrganizationResponse(
    val id: String,
    val slug: String,
    val name: String,
    val legalName: String?,
    val websiteUrl: String?,
    val contactEmail: String,
    val status: String,
    val createdAt: Instant,
)

data class AuditResponse(
    val id: String,
    val eventType: String,
    val actorId: String?,
    val subjectId: String?,
    val clientId: String?,
    val requestId: String,
    val ipAddress: String?,
    val details: Any,
    val previousHash: String?,
    val eventHash: String,
    val createdAt: Instant,
)

data class SecurityPolicyResponse(
    val key: String,
    val value: Any,
    val updatedBy: String?,
    val updatedAt: Instant,
)

data class SecurityPolicyUpdate(val value: Map<String, Any?>)

data class ConsentUiSettingsResponse(
    val layout: String,
    val availableLayouts: List<String>,
)

data class ConsentUiSettingsUpdate(val layout: String)

@Service
class AdminGuard(
    private val sessions: SessionService,
    private val limiter: RateLimiter,
    private val properties: OdsProperties,
    private val audit: AuditService,
) {
    fun require(request: HttpServletRequest): CurrentPrincipal {
        val principal = sessions.current()
        limiter.enforce(RateLimiter.ADMIN, principal.user.id)
        fun deny(code: String, message: String, reason: String): Nothing {
            audit.write(
                principal.user.tenantId,
                request,
                "ADMIN_ACCESS_DENIED",
                principal.user.id,
                principal.user.id,
                details = mapOf("reason" to reason),
            )
            throw AppException(HttpStatus.FORBIDDEN, code, message)
        }
        if (principal.user.role !in setOf("admin", "security_admin")) {
            deny("admin_required", "Administrator access is required", "role")
        }
        if (principal.session.mfaCompletedAt == null) {
            deny("mfa_required", "Administrator MFA or passkey authentication is required", "mfa")
        }
        if (principal.session.stepUpAt?.plus(properties.stepUpTtl, ChronoUnit.SECONDS)?.isAfter(Instant.now()) != true) {
            deny("step_up_required", "Recent step-up authentication is required", "step_up")
        }
        return principal
    }
}

@Service
class AdminService(
    private val guard: AdminGuard,
    private val users: UserRepository,
    private val sessions: UserSessionRepository,
    private val loginHistory: LoginHistoryRepository,
    private val auditLogs: AuditLogRepository,
    private val partnerOrganizations: PartnerOrganizationRepository,
    private val partnerApplications: PartnerApplicationRepository,
    private val policies: SecurityPolicyRepository,
    private val clients: RegisteredClientRepository,
    private val oauthProvisioning: OAuthClientProvisioningService,
    private val jdbc: JdbcOperations,
    private val crypto: CryptoService,
    private val mfa: MfaService,
    private val sessionService: SessionService,
    private val audit: AuditService,
    private val objectMapper: ObjectMapper,
    private val properties: OdsProperties,
) {
    fun dashboard(request: HttpServletRequest): AdminDashboardResponse {
        val principal = guard.require(request)
        val since = Instant.now().minus(24, ChronoUnit.HOURS)
        return AdminDashboardResponse(
            usersTotal = users.countByTenantIdAndStatusNot(principal.user.tenantId, "deleted"),
            usersActive = users.countByTenantIdAndStatus(principal.user.tenantId, "active"),
            activeSessions = sessions.countByTenantIdAndRevokedAtIsNullAndExpiresAtAfter(principal.user.tenantId, Instant.now()),
            oauthClients = jdbc.queryForObject("select count(*) from oauth2_registered_client", Long::class.java) ?: 0,
            failedLogins24h = loginHistory.countByTenantIdAndSuccessFalseAndCreatedAtAfter(principal.user.tenantId, since),
            auditEvents24h = auditLogs.countByTenantIdAndCreatedAtAfter(principal.user.tenantId, since),
        )
    }

    fun listUsers(request: HttpServletRequest, query: String?, offset: Int, limit: Int): List<UserResponse> {
        val principal = guard.require(request)
        val normalizedQuery = query?.trim()?.ifBlank { null }
        val rowMapper = { rs: java.sql.ResultSet, _: Int ->
            UserResponse(
                id = rs.getString("public_id"),
                email = rs.getString("email"),
                name = rs.getString("name"),
                fullNameCyrillic = rs.getString("full_name_cyrillic"),
                fullNameLatin = rs.getString("full_name_latin"),
                phone = rs.getString("phone"),
                emailVerified = rs.getTimestamp("email_verified_at") != null,
                status = rs.getString("status"),
                role = rs.getString("role"),
                mfaEnabled = rs.getBoolean("mfa_enabled"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
        return if (normalizedQuery == null) {
            jdbc.query(
                """
                select public_id, email, name, full_name_cyrillic, full_name_latin, phone, email_verified_at, status, role, mfa_enabled, created_at
                from users
                where tenant_id = ?
                  and status <> 'deleted'
                order by created_at desc
                limit ? offset ?
                """.trimIndent(),
                rowMapper,
                principal.user.tenantId,
                limit,
                offset,
            )
        } else {
            jdbc.query(
                """
                select public_id, email, name, full_name_cyrillic, full_name_latin, phone, email_verified_at, status, role, mfa_enabled, created_at
                from users
                where tenant_id = ?
                  and status <> 'deleted'
                  and (lower(email) like lower(concat('%', ?, '%'))
                       or lower(coalesce(name, '')) like lower(concat('%', ?, '%')))
                order by created_at desc
                limit ? offset ?
                """.trimIndent(),
                rowMapper,
                principal.user.tenantId,
                normalizedQuery,
                normalizedQuery,
                limit,
                offset,
            )
        }
    }

    @Transactional
    fun updateUser(
        userId: String,
        body: AdminUserUpdate,
        request: HttpServletRequest,
    ): UserResponse {
        val principal = guard.require(request)
        val user = users.findByPublicId(userId) ?: throw
            AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        if (user.tenantId != principal.user.tenantId) {
            throw AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        }
        val changes = mutableMapOf<String, String>()
        body.status?.let {
            if (it !in setOf("active", "suspended", "disabled")) {
                throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Invalid user status")
            }
            if (it != user.status) {
                user.status = it
                changes["status"] = it
                if (it != "active") sessionService.revokeAll(user.id)
            }
        }
        body.role?.let {
            if (it !in setOf("user", "support", "auditor", "security_admin", "admin")) {
                throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Invalid user role")
            }
            if (it != user.role) {
                user.role = it
                changes["role"] = it
            }
        }
        if (changes.isNotEmpty()) {
            audit.write(user.tenantId, request, "ADMIN_USER_UPDATED", principal.user.id, user.id, details = changes)
        }
        return user.toResponse()
    }

    @Transactional
    fun deleteUser(userId: String, request: HttpServletRequest): MessageResponse {
        val principal = guard.require(request)
        val user = users.findByPublicId(userId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        if (user.tenantId != principal.user.tenantId || user.status == "deleted") {
            throw AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        }
        if (user.id == principal.user.id) {
            throw AppException(
                HttpStatus.CONFLICT,
                "cannot_delete_current_admin",
                "Sign in as another administrator before deleting this account",
            )
        }
        val now = Instant.now()
        val previousEmailHash = crypto.sha256(user.email.lowercase())
        sessionService.revokeAll(user.id)
        jdbc.update("delete from oauth2_authorization where principal_name = ?", user.id)
        jdbc.update("delete from oauth2_authorization_consent where principal_name = ?", user.id)
        jdbc.update("delete from account_tokens where user_id = ?", user.id)
        jdbc.update("delete from mfa_methods where user_id = ?", user.id)
        jdbc.update("delete from backup_codes where user_id = ?", user.id)
        jdbc.update("delete from trusted_devices where user_id = ?", user.id)
        jdbc.update("delete from risk_assessments where user_id = ?", user.id)
        jdbc.update("delete from used_refresh_tokens where user_id = ?", user.id)
        jdbc.update(
            "update user_consents set status = 'revoked', revoked_at = coalesce(revoked_at, ?) where user_id = ?",
            now,
            user.id,
        )
        jdbc.update("delete from user_credentials where user_entity_user_id = ?", user.id)
        jdbc.update("delete from user_entities where id = ?", user.id)
        jdbc.update("update partner_memberships set status = 'disabled' where user_id = ?", user.id)
        user.email = deletedEmail(user.id)
        user.name = null
        user.phone = null
        user.passwordHash = crypto.hashPassword(crypto.randomUrl(48))
        user.emailVerifiedAt = null
        user.status = "deleted"
        user.role = "user"
        user.mfaEnabled = false
        user.failedLoginCount = 0
        user.lockedUntil = null
        user.updatedAt = now
        audit.write(
            user.tenantId,
            request,
            "ADMIN_USER_DELETED",
            principal.user.id,
            user.id,
            details = mapOf("previous_email_sha256" to previousEmailHash),
        )
        return MessageResponse(message = "User account deleted")
    }

    @Transactional
    fun revokeUserSessions(userId: String, request: HttpServletRequest): MessageResponse {
        val principal = guard.require(request)
        val user = users.findByPublicId(userId) ?: throw
            AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        if (user.tenantId != principal.user.tenantId) {
            throw AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        }
        val count = sessionService.revokeAll(user.id)
        jdbc.update("delete from oauth2_authorization where principal_name = ?", user.id)
        audit.write(user.tenantId, request, "ADMIN_SESSIONS_REVOKED", principal.user.id, user.id, details = mapOf("revoked_sessions" to count))
        return MessageResponse(message = "User sessions revoked")
    }

    @Transactional
    fun resetMfa(userId: String, request: HttpServletRequest): MessageResponse {
        val principal = guard.require(request)
        val user = users.findByPublicId(userId) ?: throw
            AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        if (user.tenantId != principal.user.tenantId) {
            throw AppException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found")
        }
        mfa.reset(user)
        sessionService.revokeAll(user.id)
        jdbc.update("delete from oauth2_authorization where principal_name = ?", user.id)
        audit.write(user.tenantId, request, "ADMIN_MFA_RESET", principal.user.id, user.id)
        return MessageResponse(message = "User MFA reset")
    }

    fun listClients(request: HttpServletRequest): List<OAuthClientResponse> {
        guard.require(request)
        return jdbc.query("select client_id from oauth2_registered_client order by client_id_issued_at desc") { rs, _ ->
            rawClient(rs.getString(1))?.toResponse()
        }.filterNotNull()
    }

    fun listOrganizations(request: HttpServletRequest): List<AdminOrganizationResponse> {
        val principal = guard.require(request)
        return partnerOrganizations.findByTenantIdOrderByCreatedAtDesc(principal.user.tenantId)
            .map { it.toAdminResponse() }
    }

    @Transactional
    fun createClient(body: OAuthClientCreate, request: HttpServletRequest): OAuthClientResponse {
        val principal = guard.require(request)
        validateRedirectUris(body.redirectUris)
        val rawSecret = if (body.isPublic) null else crypto.randomUrl(36)
        val clientId = newId("cli")
        val builder = RegisteredClient.withId(newId("app"))
            .clientId(clientId)
            .clientIdIssuedAt(Instant.now())
            .clientName(body.name)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .setting("description", body.description.orEmpty())
                    .setting("enabled", true)
                    .setting("tenant_id", principal.user.tenantId)
                    .build(),
            )
            .tokenSettings(defaultTokenSettings())
        body.redirectUris.forEach(builder::redirectUri)
        body.allowedScopes.forEach(builder::scope)
        if (body.isPublic) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        } else {
            builder.clientSecret("{argon2}${crypto.hashPassword(rawSecret!!)}")
            builder.clientAuthenticationMethod(
                when (body.tokenEndpointAuthMethod) {
                    "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST
                    else -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                },
            )
        }
        val client = builder.build()
        clients.save(client)
        audit.write(principal.user.tenantId, request, "OAUTH_CLIENT_CREATED", principal.user.id, client.id, client.clientId)
        return client.toResponse(rawSecret)
    }

    @Transactional
    fun updateClient(clientId: String, body: OAuthClientUpdate, request: HttpServletRequest): OAuthClientResponse {
        val principal = guard.require(request)
        val existing = rawClient(clientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "client_not_found", "OAuth client was not found")
        body.redirectUris?.let(::validateRedirectUris)
        val builder = RegisteredClient.from(existing)
        body.name?.let(builder::clientName)
        body.redirectUris?.let { values ->
            builder.redirectUris { current ->
                current.clear()
                current.addAll(values)
            }
        }
        body.allowedScopes?.let { values ->
            builder.scopes { current ->
                current.clear()
                current.addAll(values)
            }
        }
        if (body.description != null || body.enabled != null) {
            builder.clientSettings(
                ClientSettings.withSettings(existing.clientSettings.settings)
                    .setting(
                        "description",
                        body.description ?: existing.clientSettings.settings["description"]?.toString().orEmpty(),
                    )
                    .setting("enabled", body.enabled ?: enabled(existing))
                    .build(),
            )
        }
        val updated = builder.build()
        clients.save(updated)
        audit.write(principal.user.tenantId, request, "OAUTH_CLIENT_UPDATED", principal.user.id, updated.id, updated.clientId)
        return updated.toResponse()
    }

    @Transactional
    fun rotateSecret(clientId: String, request: HttpServletRequest): OAuthClientResponse {
        val principal = guard.require(request)
        val existing = rawClient(clientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "client_not_found", "Confidential OAuth client was not found")
        if (existing.clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE)) {
            throw AppException(HttpStatus.NOT_FOUND, "client_not_found", "Confidential OAuth client was not found")
        }
        val raw = crypto.randomUrl(36)
        val updated = RegisteredClient.from(existing)
            .clientSecret("{argon2}${crypto.hashPassword(raw)}")
            .build()
        clients.save(updated)
        audit.write(principal.user.tenantId, request, "OAUTH_CLIENT_SECRET_ROTATED", principal.user.id, updated.id, updated.clientId)
        return updated.toResponse(raw)
    }

    @Transactional
    fun deleteClient(clientId: String, request: HttpServletRequest): MessageResponse {
        val principal = guard.require(request)
        val existing = rawClient(clientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "client_not_found", "OAuth client was not found")
        val linkedPartnerApplication = jdbc.queryForObject(
            "select count(*) from partner_applications where registered_client_id = ?",
            Long::class.java,
            existing.id,
        ) ?: 0
        if (linkedPartnerApplication > 0) {
            throw AppException(
                HttpStatus.CONFLICT,
                "client_owned_by_partner_organization",
                "Delete the partner organization or partner application that owns this OAuth client",
            )
        }
        oauthProvisioning.delete(existing)
        audit.write(
            principal.user.tenantId,
            request,
            "OAUTH_CLIENT_DELETED",
            principal.user.id,
            existing.id,
            existing.clientId,
        )
        return MessageResponse(message = "OAuth client deleted")
    }

    @Transactional
    fun deleteOrganization(organizationId: String, request: HttpServletRequest): MessageResponse {
        val principal = guard.require(request)
        val organization = partnerOrganizations.findByPublicId(organizationId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "partner_organization_not_found", "Organization was not found")
        if (organization.tenantId != principal.user.tenantId) {
            throw AppException(HttpStatus.NOT_FOUND, "partner_organization_not_found", "Organization was not found")
        }
        val deletedApplications = deleteOrganizationClients(organization)
        partnerOrganizations.delete(organization)
        audit.write(
            principal.user.tenantId,
            request,
            "PARTNER_ORGANIZATION_DELETED",
            principal.user.id,
            organization.id,
            details = mapOf(
                "slug" to organization.slug,
                "applications_deleted" to deletedApplications,
                "source" to "admin",
            ),
        )
        return MessageResponse(message = "Organization deleted")
    }

    fun listSessions(request: HttpServletRequest, userId: String?): List<Map<String, Any?>> {
        val principal = guard.require(request)
        return sessions.findForAdmin(principal.user.tenantId, userId, PageRequest.of(0, 500)).map {
            mapOf(
                "id" to it.id,
                "user_id" to it.userId,
                "ip_address" to it.ipAddress,
                "user_agent" to it.userAgent,
                "created_at" to it.createdAt,
                "last_seen_at" to it.lastSeenAt,
                "expires_at" to it.expiresAt,
                "revoked_at" to it.revokedAt,
                "risk_score" to it.riskScore,
            )
        }
    }

    fun audit(request: HttpServletRequest, eventType: String?, actorId: String?, limit: Int): List<AuditResponse> {
        val principal = guard.require(request)
        return auditLogs.search(principal.user.tenantId, eventType, actorId, PageRequest.of(0, limit)).map {
            AuditResponse(
                it.id,
                it.eventType,
                it.actorId,
                it.subjectId,
                it.clientId,
                it.requestId,
                it.ipAddress,
                objectMapper.readValue(it.detailsJson, Any::class.java),
                it.previousHash,
                it.eventHash,
                it.createdAt,
            )
        }
    }

    fun policies(request: HttpServletRequest): List<SecurityPolicyResponse> {
        val principal = guard.require(request)
        return policies.findByTenantIdOrderByKey(principal.user.tenantId).map {
            SecurityPolicyResponse(it.key, objectMapper.readValue(it.valueJson, Any::class.java), it.updatedBy, it.updatedAt)
        }
    }

    fun consentUiSettings(request: HttpServletRequest): ConsentUiSettingsResponse {
        val principal = guard.require(request)
        return ConsentUiSettingsResponse(
            layout = consentLayout(principal.user.tenantId),
            availableLayouts = listOf(
                OdsOidcScopes.CONSENT_LAYOUT_GRANULAR,
                OdsOidcScopes.CONSENT_LAYOUT_CLASSIC,
            ),
        )
    }

    @Transactional
    fun updateConsentUiSettings(
        body: ConsentUiSettingsUpdate,
        request: HttpServletRequest,
    ): ConsentUiSettingsResponse {
        val principal = guard.require(request)
        val layout = normalizeConsentLayout(body.layout)
        val policy = policies.findByTenantIdAndKey(principal.user.tenantId, OdsOidcScopes.CONSENT_UI_POLICY)
            ?: SecurityPolicyEntity(tenantId = principal.user.tenantId, key = OdsOidcScopes.CONSENT_UI_POLICY)
        policy.valueJson = objectMapper.writeValueAsString(mapOf("layout" to layout))
        policy.updatedBy = principal.user.id
        policy.updatedAt = Instant.now()
        policies.save(policy)
        audit.write(
            principal.user.tenantId,
            request,
            "CONSENT_UI_UPDATED",
            principal.user.id,
            OdsOidcScopes.CONSENT_UI_POLICY,
            details = mapOf("layout" to layout),
        )
        return ConsentUiSettingsResponse(
            layout = layout,
            availableLayouts = listOf(
                OdsOidcScopes.CONSENT_LAYOUT_GRANULAR,
                OdsOidcScopes.CONSENT_LAYOUT_CLASSIC,
            ),
        )
    }

    @Transactional
    fun updatePolicy(key: String, body: SecurityPolicyUpdate, request: HttpServletRequest): SecurityPolicyResponse {
        val principal = guard.require(request)
        val policy = policies.findByTenantIdAndKey(principal.user.tenantId, key)
            ?: SecurityPolicyEntity(tenantId = principal.user.tenantId, key = key)
        policy.valueJson = objectMapper.writeValueAsString(body.value)
        policy.updatedBy = principal.user.id
        policy.updatedAt = Instant.now()
        policies.save(policy)
        audit.write(principal.user.tenantId, request, "SECURITY_POLICY_UPDATED", principal.user.id, key, details = mapOf("policy" to key))
        return SecurityPolicyResponse(key, body.value, policy.updatedBy, policy.updatedAt)
    }

    private fun consentLayout(tenantId: String): String {
        val policy = policies.findByTenantIdAndKey(tenantId, OdsOidcScopes.CONSENT_UI_POLICY)
            ?: return OdsOidcScopes.CONSENT_LAYOUT_GRANULAR
        val value = runCatching { objectMapper.readValue(policy.valueJson, Any::class.java) }.getOrNull()
        val layout = (value as? Map<*, *>)?.get("layout")?.toString()
        return normalizeConsentLayout(layout)
    }

    private fun normalizeConsentLayout(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in OdsOidcScopes.consentLayouts }
            ?: OdsOidcScopes.CONSENT_LAYOUT_GRANULAR

    private fun RegisteredClient.toResponse(secret: String? = null): OAuthClientResponse =
        OAuthClientResponse(
            id = id,
            clientId = clientId,
            name = clientName,
            description = clientSettings.settings["description"]?.toString()?.ifBlank { null },
            redirectUris = redirectUris.sorted(),
            allowedScopes = scopes.sorted(),
            grantTypes = authorizationGrantTypes.map { it.value }.sorted(),
            tokenEndpointAuthMethod = clientAuthenticationMethods.firstOrNull()?.value ?: "none",
            isPublic = clientAuthenticationMethods.contains(ClientAuthenticationMethod.NONE),
            requirePkce = clientSettings.isRequireProofKey,
            enabled = enabled(this),
            createdAt = clientIdIssuedAt ?: Instant.EPOCH,
            clientSecret = secret,
        )

    private fun PartnerOrganizationEntity.toAdminResponse() = AdminOrganizationResponse(
        id = id,
        slug = slug,
        name = name,
        legalName = legalName,
        websiteUrl = websiteUrl,
        contactEmail = contactEmail,
        status = status,
        createdAt = createdAt,
    )

    private fun deleteOrganizationClients(organization: PartnerOrganizationEntity): Int {
        val metadata = partnerApplications.findByOrganizationIdOrderByCreatedAtDesc(organization.id)
        metadata.forEach(::deletePartnerApplicationClient)
        return metadata.size
    }

    private fun deletePartnerApplicationClient(metadata: PartnerApplicationEntity) {
        oauthProvisioning.findIncludingDisabledById(metadata.registeredClientId)
            ?.let(oauthProvisioning::delete)
    }

    private fun deletedEmail(userId: String): String =
        "deleted+${userId.removePrefix("usr_").lowercase()}@deleted.ods.local"

    private fun enabled(client: RegisteredClient): Boolean =
        client.clientSettings.settings["enabled"] as? Boolean ?: true

    private fun rawClient(clientId: String): RegisteredClient? =
        (clients as? TenantAwareRegisteredClientRepository)?.findByClientIdIncludingDisabled(clientId)
            ?: clients.findByClientId(clientId)

    private fun defaultTokenSettings(): TokenSettings = TokenSettings.builder()
        .accessTokenTimeToLive(Duration.ofSeconds(properties.accessTokenTtl))
        .refreshTokenTimeToLive(Duration.ofSeconds(properties.refreshTokenTtl))
        .reuseRefreshTokens(false)
        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
        .build()

    private fun validateRedirectUris(values: List<String>) {
        if (values.isEmpty() || values.size > 20) {
            throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "One to twenty redirect URIs are required")
        }
        values.forEach {
            if ((!it.startsWith("https://") && !it.startsWith("http://localhost") && !it.startsWith("http://127.0.0.1")) || "#" in it) {
                throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Redirect URI must use HTTPS and must not contain a fragment")
            }
        }
    }
}
