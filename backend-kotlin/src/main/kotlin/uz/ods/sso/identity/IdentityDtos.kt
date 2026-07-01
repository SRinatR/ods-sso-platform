package uz.ods.sso.identity

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

data class MessageResponse(val ok: Boolean = true, val message: String)
data class RegistrationResponse(
    val ok: Boolean = true,
    val message: String,
    val email: String? = null,
)

data class RegisterRequest(
    @field:NotBlank
    @field:Email val email: String,
)

data class LoginRequest(
    @field:Email val email: String,
    @field:Size(min = 1, max = 128) val password: String,
)

data class LoginResponse(
    val ok: Boolean = true,
    val userId: String? = null,
    val email: String? = null,
    val mfaRequired: Boolean = false,
    val challengeToken: String? = null,
)

data class VerifyEmailRequest(
    @field:Email
    @field:Size(max = 320)
    val email: String? = null,
    @field:Pattern(regexp = "^\\d{6}$")
    val code: String? = null,
)
data class ResendVerificationRequest(@field:Email val email: String)
data class ForgotPasswordRequest(@field:Email val email: String)
data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:Size(min = 12, max = 128) val newPassword: String,
)

data class StepUpRequest(
    @field:Size(min = 1, max = 128) val password: String? = null,
    val code: String? = null,
)

data class UserResponse(
    val id: String,
    val email: String,
    val name: String?,
    val firstNameCyrillic: String?,
    val lastNameCyrillic: String?,
    val patronymicCyrillic: String?,
    val firstNameLatin: String?,
    val lastNameLatin: String?,
    val patronymicLatin: String?,
    val fullNameCyrillic: String?,
    val fullNameLatin: String?,
    val profilePictureUrl: String? = null,
    val phone: String?,
    val emailVerified: Boolean,
    val status: String,
    val role: String,
    val mfaEnabled: Boolean,
    val authenticationMethod: String? = null,
    val createdAt: Instant,
)

data class ProfileUpdateRequest(
    @field:Size(max = 80) val firstNameCyrillic: String?,
    @field:Size(max = 80) val lastNameCyrillic: String?,
    @field:Size(max = 80) val patronymicCyrillic: String?,
    @field:Size(max = 80) val firstNameLatin: String?,
    @field:Size(max = 80) val lastNameLatin: String?,
    @field:Size(max = 80) val patronymicLatin: String?,
    @field:Size(max = 1000) val profilePictureUrl: String? = null,
    @field:Size(max = 32) val phone: String?,
)

data class MfaChallengeRequest(
    @field:NotBlank val challengeToken: String,
    @field:Size(min = 6, max = 32) val code: String,
    val method: String = "totp",
)

data class TotpEnableRequest(
    @field:jakarta.validation.constraints.Pattern(regexp = "^\\d{6}$") val code: String,
)

data class TotpSetupResponse(val secret: String, val provisioningUri: String, val expiresIn: Int)
data class BackupCodesResponse(val backupCodes: List<String>)
