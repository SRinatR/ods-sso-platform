package uz.ods.sso.consent

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.identity.MessageResponse

@RestController
@RequestMapping("/api/v1/account")
class ConsentController(
    private val service: ConsentService,
) {
    @GetMapping("/connected-apps")
    fun connectedApps(): List<ConnectedApplicationResponse> = service.listConnected()

    @DeleteMapping("/connected-apps/{consentId}")
    fun revoke(@PathVariable consentId: String, request: HttpServletRequest): MessageResponse =
        service.revoke(consentId, request)
}

@RestController
@RequestMapping("/api/v1/oauth")
class OAuthConsentController(
    private val service: ConsentService,
) {
    @GetMapping("/consent")
    fun details(
        @RequestParam("client_id") clientId: String,
        @RequestParam(defaultValue = "") scope: String,
    ): ConsentDetailsResponse = service.details(clientId, scope.split(" ").filter(String::isNotBlank).toSet())
}
