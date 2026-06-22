package uz.ods.sso.passkey

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

data class PasskeyResponse(
    val id: String,
    val label: String,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val backupEligible: Boolean,
    val backupState: Boolean,
)

@Service
class PasskeyAuthenticationSuccessHandler(
    private val identity: IdentityService,
    private val properties: OdsProperties,
    private val objectMapper: ObjectMapper,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        identity.loginWithPasskey(authentication.name, request, response)
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "authenticated" to true,
                "redirectUrl" to properties.accountUrl,
            ),
        )
    }
}

@RestController
@RequestMapping("/api/v1/passkeys")
class PasskeyController(
    private val sessions: SessionService,
    private val userEntities: PublicKeyCredentialUserEntityRepository,
    private val credentials: UserCredentialRepository,
    private val audit: AuditService,
) {
    @GetMapping
    fun list(): List<PasskeyResponse> {
        val principal = sessions.current()
        val passkeyUser = userEntities.findByUsername(principal.user.id) ?: return emptyList()
        return credentials.findByUserId(passkeyUser.id).map {
            PasskeyResponse(
                id = it.credentialId.toBase64UrlString(),
                label = it.label,
                createdAt = it.created,
                lastUsedAt = it.lastUsed,
                backupEligible = it.isBackupEligible,
                backupState = it.isBackupState,
            )
        }
    }

    @DeleteMapping("/{credentialId}")
    @Transactional
    fun delete(
        @PathVariable credentialId: String,
        request: HttpServletRequest,
    ): MessageResponse {
        val principal = sessions.current()
        val passkeyUser = userEntities.findByUsername(principal.user.id)
            ?: throw AppException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "passkey_not_found",
                "Passkey was not found",
            )
        val id = runCatching { Bytes(java.util.Base64.getUrlDecoder().decode(credentialId)) }.getOrElse {
            throw AppException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "passkey_not_found",
                "Passkey was not found",
            )
        }
        val credential = credentials.findByCredentialId(id)
        if (credential == null || credential.userEntityUserId != passkeyUser.id) {
            throw AppException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "passkey_not_found",
                "Passkey was not found",
            )
        }
        credentials.delete(id)
        audit.write(
            principal.user.tenantId,
            request,
            "PASSKEY_DELETED",
            principal.user.id,
            principal.user.id,
            details = mapOf("credential_id_hash" to credentialId.hashCode().toString()),
        )
        return MessageResponse(message = "Passkey deleted")
    }
}
