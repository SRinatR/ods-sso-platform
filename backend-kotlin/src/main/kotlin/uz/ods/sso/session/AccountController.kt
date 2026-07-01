package uz.ods.sso.session

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.audit.AuditService
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.identity.FullNameNormalizer
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.identity.ProfileUpdateRequest
import uz.ods.sso.identity.UserResponse
import uz.ods.sso.identity.toResponse
import uz.ods.sso.persistence.LoginHistoryRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.shared.AppException
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

data class SessionResponse(
    val id: String,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val current: Boolean,
    val mfaCompleted: Boolean,
    val stepUpValid: Boolean,
    val riskScore: Int,
)

data class LoginHistoryResponse(
    val id: String,
    val email: String,
    val success: Boolean,
    val failureReason: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val riskScore: Int,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/v1/account")
class AccountController(
    private val sessionService: SessionService,
    private val sessionRepository: UserSessionRepository,
    private val loginHistory: LoginHistoryRepository,
    private val audit: AuditService,
    private val properties: OdsProperties,
) {
    @PatchMapping("/profile")
    @Transactional
    fun updateProfile(
        @Valid @RequestBody body: ProfileUpdateRequest,
        request: HttpServletRequest,
    ): UserResponse {
        val principal = sessionService.current()
        val firstNameCyrillic = FullNameNormalizer.requireCyrillicField(body.firstNameCyrillic, "First name in Cyrillic")
        val lastNameCyrillic = FullNameNormalizer.requireCyrillicField(body.lastNameCyrillic, "Last name in Cyrillic")
        val patronymicCyrillic = FullNameNormalizer.optionalCyrillicField(
            body.patronymicCyrillic,
            "Patronymic in Cyrillic",
        )
        val firstNameLatin = FullNameNormalizer.requireLatinField(
            firstNameCyrillic,
            body.firstNameLatin,
            "First name in Latin",
        )
        val lastNameLatin = FullNameNormalizer.requireLatinField(
            lastNameCyrillic,
            body.lastNameLatin,
            "Last name in Latin",
        )
        val patronymicLatin = FullNameNormalizer.optionalLatinField(
            patronymicCyrillic,
            body.patronymicLatin,
            "Patronymic in Latin",
        )
        val requestedPhone = body.phone?.trim()?.takeIf(String::isNotEmpty)
            ?: throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_error",
                "Phone is required",
            )
        val profilePictureUrl = normalizeProfilePictureUrl(body.profilePictureUrl)
        principal.user.firstNameCyrillic = firstNameCyrillic
        principal.user.lastNameCyrillic = lastNameCyrillic
        principal.user.patronymicCyrillic = patronymicCyrillic
        principal.user.firstNameLatin = firstNameLatin
        principal.user.lastNameLatin = lastNameLatin
        principal.user.patronymicLatin = patronymicLatin
        principal.user.fullNameCyrillic = FullNameNormalizer.joinParts(
            firstNameCyrillic,
            lastNameCyrillic,
            patronymicCyrillic,
        )
        principal.user.fullNameLatin = FullNameNormalizer.joinParts(firstNameLatin, lastNameLatin, patronymicLatin)
        principal.user.name = firstNameCyrillic
        principal.user.profilePictureUrl = profilePictureUrl
        principal.user.phone = requestedPhone
        principal.user.updatedAt = Instant.now()
        audit.write(
            principal.user.tenantId,
            request,
            "PROFILE_UPDATED",
            principal.user.id,
            principal.user.id,
        )
        return principal.user.toResponse()
    }

    private fun normalizeProfilePictureUrl(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.fragment != null) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_error",
                "Profile picture URL must be an absolute HTTPS URL without a fragment",
            )
        }
        return normalized
    }

    @GetMapping("/sessions")
    fun sessions(): List<SessionResponse> {
        val principal = sessionService.current()
        val now = Instant.now()
        return sessionRepository.findActiveByUserId(principal.user.id, now).map {
            SessionResponse(
                id = it.id,
                ipAddress = it.ipAddress,
                userAgent = it.userAgent,
                createdAt = it.createdAt,
                lastSeenAt = it.lastSeenAt,
                expiresAt = it.expiresAt,
                current = it.id == principal.session.id,
                mfaCompleted = it.mfaCompletedAt != null,
                stepUpValid = it.stepUpAt?.plus(properties.stepUpTtl, ChronoUnit.SECONDS)?.isAfter(now) == true,
                riskScore = it.riskScore,
            )
        }
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun revoke(
        @PathVariable sessionId: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MessageResponse {
        val principal = sessionService.current()
        if (!sessionService.revoke(principal.user.id, sessionId)) {
            throw AppException(HttpStatus.NOT_FOUND, "session_not_found", "Session was not found")
        }
        if (principal.session.id == sessionId) sessionService.clearCookie(response)
        audit.write(
            principal.user.tenantId,
            request,
            "SESSION_REVOKED",
            principal.user.id,
            principal.user.id,
            details = mapOf("session_id" to sessionId),
        )
        return MessageResponse(message = "Session revoked")
    }

    @GetMapping("/login-history")
    fun history(): List<LoginHistoryResponse> {
        val principal = sessionService.current()
        return loginHistory.findByUserIdOrderByCreatedAtDesc(principal.user.id, PageRequest.of(0, 200)).map {
            LoginHistoryResponse(
                id = it.id,
                email = it.email,
                success = it.success,
                failureReason = it.failureReason,
                ipAddress = it.ipAddress,
                userAgent = it.userAgent,
                riskScore = it.riskScore,
                createdAt = it.createdAt,
            )
        }
    }
}
