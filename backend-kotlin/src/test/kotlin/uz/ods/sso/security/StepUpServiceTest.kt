package uz.ods.sso.security

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserSessionEntity
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant

class StepUpServiceTest {
    private val sessions = mock<SessionService>()
    private val crypto = mock<CryptoService>()
    private val mfa = mock<MfaService>()
    private val properties = OdsProperties(
        environment = "test",
        stepUpTtl = 600,
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val service = StepUpService(sessions, crypto, mfa, properties)
    private val user = UserEntity(
        tenantId = "tnt_1",
        email = "admin@example.com",
        passwordHash = "hash",
        mfaEnabled = true,
    ).apply { publicId = "usr_1" }

    @Test
    fun `fresh passkey satisfies step up without password or TOTP`() {
        val session = UserSessionEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            authenticationMethod = "passkey",
            stepUpAt = Instant.now(),
        ).apply { publicId = "ses_1" }
        val principal = CurrentPrincipal(user, session)

        service.verifyAndMark(principal, null, null)

        verify(sessions).markStepUp("ses_1")
        verify(crypto, never()).matchesPassword(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(mfa, never()).verifyStepUp(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `expired passkey requires a new WebAuthn assertion`() {
        val session = UserSessionEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            authenticationMethod = "passkey",
            stepUpAt = Instant.now().minusSeconds(601),
        ).apply { publicId = "ses_1" }

        assertThatThrownBy { service.verifyAndMark(CurrentPrincipal(user, session), null, null) }
            .isInstanceOf(AppException::class.java)
            .hasMessage("Step-up authentication failed")
        verify(sessions, never()).markStepUp("ses_1")
    }

    @Test
    fun `password session requires password and TOTP`() {
        val session = UserSessionEntity(
            tenantId = "tnt_1",
            userId = "usr_1",
            authenticationMethod = "password_totp",
        ).apply { publicId = "ses_1" }
        val principal = CurrentPrincipal(user, session)
        whenever(crypto.matchesPassword("password", "hash")).thenReturn(true)

        service.verifyAndMark(principal, "password", "123456")

        verify(mfa).verifyStepUp(user, "123456")
        verify(sessions).markStepUp("ses_1")
    }
}
