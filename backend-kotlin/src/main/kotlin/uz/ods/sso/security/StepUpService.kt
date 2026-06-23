package uz.ods.sso.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.mfa.MfaService
import uz.ods.sso.session.CurrentPrincipal
import uz.ods.sso.session.SessionService
import uz.ods.sso.shared.AppException
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class StepUpService(
    private val sessions: SessionService,
    private val crypto: CryptoService,
    private val mfa: MfaService,
    private val properties: OdsProperties,
) {
    fun verifyAndMark(
        principal: CurrentPrincipal,
        password: String?,
        code: String?,
    ) {
        if (!hasFreshPasskey(principal)) {
            if (password.isNullOrBlank() || !crypto.matchesPassword(password, principal.user.passwordHash)) {
                throw AppException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Step-up authentication failed")
            }
            mfa.verifyStepUp(principal.user, code)
        }
        sessions.markStepUp(principal.session.id)
    }

    fun hasFreshPasskey(principal: CurrentPrincipal): Boolean =
        principal.session.authenticationMethod == "passkey" &&
            principal.session.stepUpAt
                ?.plus(properties.stepUpTtl, ChronoUnit.SECONDS)
                ?.isAfter(Instant.now()) == true
}
