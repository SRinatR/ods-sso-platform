package uz.ods.sso.risk

import org.springframework.stereotype.Service
import uz.ods.sso.persistence.RiskAssessmentEntity
import uz.ods.sso.persistence.RiskAssessmentRepository
import uz.ods.sso.persistence.TrustedDeviceEntity
import uz.ods.sso.persistence.TrustedDeviceRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.security.CryptoService
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class RiskResult(
    val score: Int,
    val decision: String,
    val reasons: List<String>,
    val fingerprint: String,
)

@Service
class RiskService(
    private val devices: TrustedDeviceRepository,
    private val assessments: RiskAssessmentRepository,
    private val crypto: CryptoService,
    private val objectMapper: ObjectMapper,
) {
    fun assess(user: UserEntity, ipAddress: String, userAgent: String?): RiskResult {
        val fingerprint = crypto.fingerprint(userAgent, ipAddress)
        val existing = devices.findByUserIdAndFingerprint(user.id, fingerprint)
        val reasons = mutableListOf<String>()
        var score = 0
        if (existing == null) {
            score += 25
            reasons += "new_device"
        } else if (!existing.trusted) {
            score += 15
            reasons += "untrusted_device"
        }
        if (userAgent.isNullOrBlank()) {
            score += 20
            reasons += "missing_user_agent"
        }
        val decision = when {
            score >= 80 -> "deny"
            score >= 40 -> "step_up"
            else -> "allow"
        }
        assessments.save(
            RiskAssessmentEntity(
                tenantId = user.tenantId,
                userId = user.id,
                score = score,
                decision = decision,
                reasonsJson = objectMapper.writeValueAsString(reasons),
            ),
        )
        if (existing == null) {
            devices.save(
                TrustedDeviceEntity(
                    tenantId = user.tenantId,
                    userId = user.id,
                    fingerprint = fingerprint,
                    lastUserAgent = userAgent,
                    lastIpAddress = ipAddress,
                ),
            )
        } else {
            existing.lastSeenAt = Instant.now()
            existing.lastIpAddress = ipAddress
            existing.lastUserAgent = userAgent
        }
        return RiskResult(score, decision, reasons, fingerprint)
    }

    fun trust(userId: String, fingerprint: String?) {
        if (fingerprint == null) return
        devices.findByUserIdAndFingerprint(userId, fingerprint)?.trusted = true
    }
}
