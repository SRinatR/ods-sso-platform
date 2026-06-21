package uz.ods.sso.mfa

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.identity.IdentityService
import uz.ods.sso.persistence.BackupCodeEntity
import uz.ods.sso.persistence.BackupCodeRepository
import uz.ods.sso.persistence.MfaMethodEntity
import uz.ods.sso.persistence.MfaMethodRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.risk.RiskService
import uz.ods.sso.security.CryptoService
import uz.ods.sso.security.EphemeralStore
import uz.ods.sso.shared.AppException
import java.time.Duration
import java.time.Instant

@Service
class MfaService(
    private val methods: MfaMethodRepository,
    private val backupCodes: BackupCodeRepository,
    private val users: UserRepository,
    private val crypto: CryptoService,
    private val ephemeral: EphemeralStore,
    private val identity: IdentityService,
    private val risk: RiskService,
) {
    fun setup(user: UserEntity): Pair<String, String> {
        val secret = crypto.newTotpSecret()
        ephemeral.set(
            "mfa:totp:pending:${user.id}",
            crypto.encrypt(secret, "pending-totp:${user.id}"),
            Duration.ofMinutes(10),
        )
        return secret to crypto.provisioningUri(secret, user.email)
    }

    @Transactional
    fun enable(user: UserEntity, code: String): List<String> {
        val encrypted = ephemeral.get("mfa:totp:pending:${user.id}")
            ?: throw AppException(HttpStatus.BAD_REQUEST, "totp_setup_expired", "TOTP setup has expired")
        val secret = crypto.decrypt(encrypted, "pending-totp:${user.id}")
        if (!crypto.verifyTotp(secret, code)) {
            throw AppException(HttpStatus.BAD_REQUEST, "invalid_otp", "One-time password is invalid")
        }
        val method = methods.findByUserIdAndMethodType(user.id, "totp")
            ?: MfaMethodEntity(userId = user.id, methodType = "totp")
        method.secretEncrypted = crypto.encrypt(secret, "totp:${user.id}")
        method.enabled = true
        method.verifiedAt = Instant.now()
        methods.save(method)
        user.mfaEnabled = true
        users.save(user)
        ephemeral.delete("mfa:totp:pending:${user.id}")
        return regenerateBackupCodes(user.id)
    }

    @Transactional
    fun regenerateBackupCodes(userId: String): List<String> {
        backupCodes.deleteByUserId(userId)
        val values = (1..10).map { "${crypto.randomUrl(6)}-${crypto.randomUrl(6)}" }
        backupCodes.saveAll(values.map { BackupCodeEntity(userId = userId, codeHash = crypto.hashSecret(it)) })
        return values
    }

    fun verifyTotp(userId: String, code: String): Boolean {
        val method = methods.findByUserIdAndMethodTypeAndEnabledTrue(userId, "totp") ?: return false
        val secret = crypto.decrypt(method.secretEncrypted, "totp:$userId")
        return crypto.verifyTotp(secret, code)
    }

    @Transactional
    fun verifyBackupCode(userId: String, code: String): Boolean {
        val match = backupCodes.findByUserId(userId)
            .firstOrNull { it.usedAt == null && crypto.secretMatches(code, it.codeHash) }
            ?: return false
        match.usedAt = Instant.now()
        return true
    }

    @Transactional
    fun verifyLoginChallenge(challengeToken: String, code: String, method: String): ChallengeResult {
        val (user, riskScore, fingerprint) = identity.challenge(challengeToken)
        val valid = when (method) {
            "totp" -> verifyTotp(user.id, code)
            "backup" -> verifyBackupCode(user.id, code)
            else -> false
        }
        if (!valid) throw AppException(HttpStatus.UNAUTHORIZED, "invalid_otp", "Second factor is invalid")
        identity.consumeChallenge(challengeToken)
        risk.trust(user.id, fingerprint)
        return ChallengeResult(user, riskScore, fingerprint)
    }

    fun verifyStepUp(user: UserEntity, code: String?) {
        if (!user.mfaEnabled) throw AppException(HttpStatus.FORBIDDEN, "mfa_required", "MFA must be enabled for step-up")
        if (code == null || !verifyTotp(user.id, code)) {
            throw AppException(HttpStatus.UNAUTHORIZED, "invalid_otp", "Second factor is invalid")
        }
    }

    @Transactional
    fun reset(user: UserEntity) {
        methods.deleteByUserId(user.id)
        backupCodes.deleteByUserId(user.id)
        user.mfaEnabled = false
        users.save(user)
    }
}

data class ChallengeResult(val user: UserEntity, val riskScore: Int, val fingerprint: String?)
