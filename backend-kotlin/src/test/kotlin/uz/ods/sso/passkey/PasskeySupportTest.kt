package uz.ods.sso.passkey

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import tools.jackson.databind.json.JsonMapper
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

class PasskeySupportTest {
    private val user = UserEntity(
        tenantId = "tnt_1",
        email = "user@example.com",
        emailVerifiedAt = Instant.now(),
    ).apply { publicId = "usr_1" }
    private val session = UserSessionEntity(tenantId = "tnt_1", userId = "usr_1").apply {
        publicId = "ses_1"
    }

    @Test
    fun `authentication success creates application session and JSON response`() {
        val identity = mock<IdentityService>()
        val properties = OdsProperties(
            accountUrl = "https://accounts.ods.uz",
            sessionSecret = "session-secret-that-is-longer-than-32-characters",
            tokenPepper = "token-pepper-that-is-independent-and-long",
        )
        val handler = PasskeyAuthenticationSuccessHandler(identity, properties, JsonMapper.builder().build())
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(
            request,
            response,
            UsernamePasswordAuthenticationToken.authenticated("usr_1", null, emptyList()),
        )

        verify(identity).loginWithPasskey("usr_1", request, response)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsString).contains("\"authenticated\":true", "https://accounts.ods.uz")
    }

    @Test
    fun `controller lists and deletes only the current users passkeys`() {
        val sessions = mock<SessionService>()
        val userEntities = mock<PublicKeyCredentialUserEntityRepository>()
        val credentials = mock<UserCredentialRepository>()
        val audit = mock<AuditService>()
        val passkeyUser = mock<PublicKeyCredentialUserEntity>()
        val credential = mock<CredentialRecord>()
        val userId = Bytes(byteArrayOf(1, 2, 3))
        val credentialId = Bytes(byteArrayOf(4, 5, 6))
        whenever(sessions.current()).thenReturn(CurrentPrincipal(user, session))
        whenever(userEntities.findByUsername("usr_1")).thenReturn(passkeyUser)
        whenever(passkeyUser.id).thenReturn(userId)
        whenever(credentials.findByUserId(userId)).thenReturn(listOf(credential))
        whenever(credential.credentialId).thenReturn(credentialId)
        whenever(credential.userEntityUserId).thenReturn(userId)
        whenever(credential.label).thenReturn("Laptop")
        whenever(credential.created).thenReturn(Instant.parse("2026-06-22T10:00:00Z"))
        whenever(credential.lastUsed).thenReturn(Instant.parse("2026-06-22T11:00:00Z"))
        whenever(credential.isBackupEligible).thenReturn(true)
        whenever(credential.isBackupState).thenReturn(false)
        whenever(credentials.findByCredentialId(any())).thenReturn(credential)
        val controller = PasskeyController(sessions, userEntities, credentials, audit)

        val listed = controller.list().single()
        assertThat(listed.label).isEqualTo("Laptop")
        assertThat(listed.backupEligible).isTrue()

        assertThat(controller.delete(credentialId.toBase64UrlString(), MockHttpServletRequest()).ok).isTrue()
        verify(credentials).delete(credentialId)
        verify(audit).write(
            eq("tnt_1"),
            any(),
            eq("PASSKEY_DELETED"),
            eq("usr_1"),
            eq("usr_1"),
            isNull(),
            details = any(),
        )
    }

    @Test
    fun `controller returns empty list and rejects invalid passkey identifiers`() {
        val sessions = mock<SessionService>()
        val userEntities = mock<PublicKeyCredentialUserEntityRepository>()
        val credentials = mock<UserCredentialRepository>()
        val controller = PasskeyController(sessions, userEntities, credentials, mock<AuditService>())
        whenever(sessions.current()).thenReturn(CurrentPrincipal(user, session))
        whenever(userEntities.findByUsername("usr_1")).thenReturn(null)

        assertThat(controller.list()).isEmpty()
        assertThatThrownBy { controller.delete("not-base64!", MockHttpServletRequest()) }
            .isInstanceOf(AppException::class.java)
            .hasMessage("Passkey was not found")
    }
}
