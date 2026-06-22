package uz.ods.sso.mfa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.persistence.BackupCodeEntity
import uz.ods.sso.persistence.BackupCodeRepository
import uz.ods.sso.persistence.MfaMethodRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.risk.RiskService
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.EphemeralStore
import uz.ods.sso.shared.AppException
import java.time.Duration
import java.time.Instant

class MfaServiceTest {
    private val properties = OdsProperties(
        environment = "test",
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val methods = mock<MfaMethodRepository>()
    private val backupCodes = mock<BackupCodeRepository>()
    private val users = mock<UserRepository>()
    private val crypto = CryptoService(properties)
    private val ephemeral = EphemeralStore(mock<StringRedisTemplate>(), properties)
    private val identity = mock<IdentityService>()
    private val risk = mock<RiskService>()
    private val service = MfaService(methods, backupCodes, users, crypto, ephemeral, identity, risk)
    private val user = UserEntity(tenantId = "tnt_1", email = "user@example.com").apply { publicId = "usr_1" }

    @Test
    fun `setup stores encrypted pending secret and rejects invalid confirmation`() {
        val (secret, uri) = service.setup(user)

        assertThat(secret).isNotBlank()
        assertThat(uri).startsWith("otpauth://totp/")
        assertThatThrownBy { service.enable(user, "000000") }
            .isInstanceOf(AppException::class.java)
            .hasMessage("One-time password is invalid")
    }

    @Test
    fun `backup codes are regenerated consumed and reset`() {
        whenever(backupCodes.saveAll(any<List<BackupCodeEntity>>())).thenAnswer { it.arguments[0] }
        val generated = service.regenerateBackupCodes(user.id)
        assertThat(generated).hasSize(10).doesNotHaveDuplicates()
        verify(backupCodes).deleteByUserId(user.id)

        val raw = "backup-code"
        val entity = BackupCodeEntity(userId = user.id, codeHash = crypto.hashSecret(raw))
        whenever(backupCodes.findByUserId(user.id)).thenReturn(listOf(entity))
        assertThat(service.verifyBackupCode(user.id, raw)).isTrue()
        assertThat(entity.usedAt).isNotNull()
        assertThat(service.verifyBackupCode(user.id, raw)).isFalse()

        user.mfaEnabled = true
        service.reset(user)
        assertThat(user.mfaEnabled).isFalse()
        verify(methods).deleteByUserId(user.id)
        verify(users).save(user)
    }

    @Test
    fun `missing factors fail closed`() {
        whenever(methods.findByUserIdAndMethodTypeAndEnabledTrue(user.id, "totp")).thenReturn(null)

        assertThat(service.verifyTotp(user.id, "123456")).isFalse()
        assertThatThrownBy { service.verifyStepUp(user, "123456") }
            .isInstanceOf(AppException::class.java)
            .hasMessage("MFA must be enabled for step-up")

        whenever(identity.challenge("challenge")).thenReturn(Triple(user, 25, "fingerprint"))
        assertThat(service.loginChallengeRateLimitIdentity("challenge")).isEqualTo("usr_1")
        assertThatThrownBy { service.verifyLoginChallenge("challenge", "bad", "unknown") }
            .isInstanceOf(AppException::class.java)
            .hasMessage("Second factor is invalid")
    }

    @Test
    fun `rate limit lock persists for thirty minutes without extending an active lock`() {
        whenever(identity.challenge("challenge")).thenReturn(Triple(user, 25, "fingerprint"))
        whenever(users.save(user)).thenReturn(user)
        val before = Instant.now().plus(Duration.ofMinutes(29))

        assertThat(service.lockLoginChallenge("challenge", Duration.ofMinutes(30))).isSameAs(user)
        assertThat(user.lockedUntil).isAfter(before)
        assertThat(service.lockLoginChallenge("challenge", Duration.ofMinutes(30))).isNull()
        verify(users).save(user)
    }

    @Test
    fun `locked account cannot verify an existing MFA challenge`() {
        user.lockedUntil = Instant.now().plus(Duration.ofMinutes(30))
        whenever(identity.challenge("challenge")).thenReturn(Triple(user, 25, "fingerprint"))

        assertThatThrownBy { service.verifyLoginChallenge("challenge", "123456", "totp") }
            .isInstanceOfSatisfying(AppException::class.java) {
                assertThat(it.status.value()).isEqualTo(423)
                assertThat(it.code).isEqualTo("account_locked")
            }
    }

    @Test
    fun `invalid challenge cannot be used to select an account for lockout`() {
        whenever(identity.challenge("invalid")).thenThrow(
            AppException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "invalid_mfa_challenge",
                "MFA challenge is invalid",
            ),
        )

        assertThat(service.lockLoginChallenge("invalid", Duration.ofMinutes(30))).isNull()
    }
}
