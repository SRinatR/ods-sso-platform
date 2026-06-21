package uz.ods.sso.risk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.json.JsonMapper
import uz.ods.sso.persistence.RiskAssessmentRepository
import uz.ods.sso.persistence.RiskAssessmentEntity
import uz.ods.sso.persistence.TrustedDeviceEntity
import uz.ods.sso.persistence.TrustedDeviceRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.security.CryptoService
import uz.ods.sso.config.OdsProperties

class RiskServiceTest {
    private val devices: TrustedDeviceRepository = mock()
    private val assessments: RiskAssessmentRepository = mock()
    private val crypto = CryptoService(
        OdsProperties(
            sessionSecret = "session-secret-that-is-longer-than-32-characters",
            tokenPepper = "token-pepper-that-is-independent-and-long",
        ),
    )
    private val service = RiskService(devices, assessments, crypto, JsonMapper.builder().build())
    private val user = UserEntity(tenantId = "tnt_1", email = "user@example.com")

    @Test
    fun `new browser requires no step-up until risk threshold`() {
        val fingerprint = crypto.fingerprint("Browser/1", "192.168.1.50")
        whenever(devices.findByUserIdAndFingerprint(user.id, fingerprint)).thenReturn(null)
        whenever(devices.save(any<TrustedDeviceEntity>())).thenAnswer { it.arguments[0] as TrustedDeviceEntity }
        whenever(assessments.save(any<RiskAssessmentEntity>())).thenAnswer { it.arguments[0] as RiskAssessmentEntity }

        val result = service.assess(user, "192.168.1.50", "Browser/1")

        assertThat(result.score).isEqualTo(25)
        assertThat(result.decision).isEqualTo("allow")
        assertThat(result.reasons).containsExactly("new_device")
    }

    @Test
    fun `missing user agent raises step-up decision`() {
        val fingerprint = crypto.fingerprint(null, "192.168.1.50")
        whenever(devices.findByUserIdAndFingerprint(user.id, fingerprint)).thenReturn(null)
        whenever(devices.save(any<TrustedDeviceEntity>())).thenAnswer { it.arguments[0] as TrustedDeviceEntity }
        whenever(assessments.save(any<RiskAssessmentEntity>())).thenAnswer { it.arguments[0] as RiskAssessmentEntity }

        val result = service.assess(user, "192.168.1.50", null)

        assertThat(result.score).isEqualTo(45)
        assertThat(result.decision).isEqualTo("step_up")
        assertThat(result.reasons).containsExactly("new_device", "missing_user_agent")
    }

    @Test
    fun `trusted device has zero risk`() {
        val fingerprint = crypto.fingerprint("Browser/1", "192.168.1.50")
        val existing = TrustedDeviceEntity(
            tenantId = user.tenantId,
            userId = user.id,
            fingerprint = fingerprint,
            trusted = true,
        )
        whenever(devices.findByUserIdAndFingerprint(user.id, fingerprint)).thenReturn(existing)
        whenever(assessments.save(any<RiskAssessmentEntity>())).thenAnswer { it.arguments[0] as RiskAssessmentEntity }
        val result = service.assess(user, "192.168.1.50", "Browser/1")

        assertThat(result.score).isZero()
        assertThat(result.decision).isEqualTo("allow")
        assertThat(result.reasons).isEmpty()
    }
}
