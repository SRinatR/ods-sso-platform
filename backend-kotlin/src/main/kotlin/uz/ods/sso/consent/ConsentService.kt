package uz.ods.sso.consent

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.audit.AuditService
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.persistence.UserConsentEntity
import uz.ods.sso.persistence.UserConsentRepository
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

data class ConnectedApplicationResponse(
    val consentId: String,
    val clientId: String,
    val name: String,
    val scopes: List<String>,
    val grantedAt: Instant,
)

data class ConsentDetailsResponse(
    val clientId: String,
    val clientName: String,
    val clientDescription: String?,
    val logoUri: String?,
    val hideOdsBranding: Boolean,
    val requestedScopes: List<String>,
    val newScopes: List<String>,
    val dataFields: List<ConsentDataField>,
)

data class ConsentDataField(
    val scope: String,
    val label: String,
    val fields: List<String>,
)

@Service
class ConsentService(
    private val consents: UserConsentRepository,
    private val clients: RegisteredClientRepository,
    private val authorizationConsents: OAuth2AuthorizationConsentService,
    private val jdbc: JdbcOperations,
    private val sessions: SessionService,
    private val audit: AuditService,
) {
    fun listConnected(): List<ConnectedApplicationResponse> {
        val principal = sessions.current()
        return consents.findByUserIdAndStatus(principal.user.id, "granted").mapNotNull { consent ->
            val client = clients.findByClientId(consent.clientId) ?: return@mapNotNull null
            ConnectedApplicationResponse(
                consentId = consent.id,
                clientId = consent.clientId,
                name = client.clientName,
                scopes = consent.scopes.split(" ").filter(String::isNotBlank),
                grantedAt = consent.grantedAt,
            )
        }
    }

    fun details(clientId: String, requestedScopes: Set<String>): ConsentDetailsResponse {
        val principal = sessions.current()
        val client = clients.findByClientId(clientId)
            ?: throw AppException(HttpStatus.NOT_FOUND, "client_not_found", "OAuth client was not found")
        val existing = authorizationConsents.findById(client.id, principal.user.id)?.scopes.orEmpty()
        return ConsentDetailsResponse(
            clientId = clientId,
            clientName = client.clientName,
            clientDescription = client.clientSettings.settings["description"]?.toString(),
            logoUri = client.clientSettings.settings["logo_uri"]?.toString()?.ifBlank { null },
            hideOdsBranding = client.clientSettings.settings["hide_ods_branding"] as? Boolean ?: false,
            requestedScopes = requestedScopes.sorted(),
            newScopes = (requestedScopes - existing).sorted(),
            dataFields = requestedScopes.sorted().map(::dataFieldForScope),
        )
    }

    private fun dataFieldForScope(scope: String): ConsentDataField =
        when (scope) {
            "openid" -> ConsentDataField(scope, "Идентификатор аккаунта", listOf("Уникальный ID пользователя ODS"))
            "profile" -> ConsentDataField(scope, "Профиль", listOf("Имя", "Роль в организации", "Права доступа"))
            "email" -> ConsentDataField(scope, "Email", listOf("Email", "Статус подтверждения email"))
            "phone" -> ConsentDataField(scope, "Телефон", listOf("Номер телефона, если он заполнен"))
            "offline_access" -> ConsentDataField(
                scope,
                "Долгая сессия",
                listOf("Refresh token для обновления доступа без повторного входа"),
            )
            else -> ConsentDataField(scope, scope, listOf("Данные, связанные со scope $scope"))
        }

    @Transactional
    fun synchronize(userId: String, tenantId: String, clientId: String, scopes: Set<String>) {
        val entity = consents.findByUserIdAndClientId(userId, clientId)
            ?: UserConsentEntity(tenantId = tenantId, userId = userId, clientId = clientId)
        entity.scopes = scopes.sorted().joinToString(" ")
        entity.status = "granted"
        entity.revokedAt = null
        entity.grantedAt = Instant.now()
        consents.save(entity)
    }

    @Transactional
    fun revoke(consentId: String, request: HttpServletRequest): MessageResponse {
        val principal = sessions.current()
        val consent = consents.findByPublicIdAndUserId(consentId, principal.user.id)
            ?: throw AppException(HttpStatus.NOT_FOUND, "connected_application_not_found", "Application was not found")
        if (consent.status != "granted") {
            throw AppException(HttpStatus.NOT_FOUND, "connected_application_not_found", "Application was not found")
        }
        val registeredClient = clients.findByClientId(consent.clientId)
        if (registeredClient != null) {
            authorizationConsents.remove(
                authorizationConsents.findById(registeredClient.id, principal.user.id)
                    ?: org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
                        .withId(registeredClient.id, principal.user.id)
                        .build(),
            )
            jdbc.update(
                "delete from oauth2_authorization where registered_client_id = ? and principal_name = ?",
                registeredClient.id,
                principal.user.id,
            )
        }
        consent.status = "revoked"
        consent.revokedAt = Instant.now()
        audit.write(
            principal.user.tenantId,
            request,
            "CONSENT_REVOKED",
            principal.user.id,
            principal.user.id,
            consent.clientId,
            mapOf("consent_id" to consent.id),
        )
        return MessageResponse(message = "Application access revoked")
    }
}

open class MirroringAuthorizationConsentService(
    private val delegate: OAuth2AuthorizationConsentService,
    private val consents: UserConsentRepository,
    private val users: uz.ods.sso.persistence.UserRepository,
    private val clients: RegisteredClientRepository,
    private val audit: AuditService,
) : OAuth2AuthorizationConsentService {
    @Transactional
    open override fun save(authorizationConsent: OAuth2AuthorizationConsent) {
        delegate.save(authorizationConsent)
        val user = users.findByPublicId(authorizationConsent.principalName) ?: return
        val client = clients.findById(authorizationConsent.registeredClientId) ?: return
        val entity = consents.findByUserIdAndClientId(user.id, client.clientId)
            ?: UserConsentEntity(tenantId = user.tenantId, userId = user.id, clientId = client.clientId)
        entity.scopes = authorizationConsent.scopes.sorted().joinToString(" ")
        entity.status = "granted"
        entity.grantedAt = Instant.now()
        entity.revokedAt = null
        consents.save(entity)
        audit.writeSystem(
            user.tenantId,
            "CONSENT_GRANTED",
            actorId = user.id,
            subjectId = user.id,
            clientId = client.clientId,
            details = mapOf("scopes" to authorizationConsent.scopes.sorted()),
        )
    }

    @Transactional
    open override fun remove(authorizationConsent: OAuth2AuthorizationConsent) {
        delegate.remove(authorizationConsent)
        val user = users.findByPublicId(authorizationConsent.principalName) ?: return
        val client = clients.findById(authorizationConsent.registeredClientId) ?: return
        consents.findByUserIdAndClientId(user.id, client.clientId)?.let {
            it.status = "revoked"
            it.revokedAt = Instant.now()
        }
    }

    override fun findById(
        registeredClientId: String,
        principalName: String,
    ): OAuth2AuthorizationConsent? = delegate.findById(registeredClientId, principalName)
}
