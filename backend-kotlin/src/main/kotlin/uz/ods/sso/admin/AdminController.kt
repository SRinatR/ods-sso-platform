package uz.ods.sso.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.identity.MessageResponse
import uz.ods.sso.identity.UserResponse

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val service: AdminService,
) {
    @GetMapping("/dashboard")
    fun dashboard(request: HttpServletRequest) = service.dashboard(request)

    @GetMapping("/users")
    fun users(
        request: HttpServletRequest,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<UserResponse> = service.listUsers(request, query, offset.coerceAtLeast(0), limit.coerceIn(1, 500))

    @PatchMapping("/users/{userId}")
    fun updateUser(
        @PathVariable userId: String,
        @RequestBody body: AdminUserUpdate,
        request: HttpServletRequest,
    ) = service.updateUser(userId, body, request)

    @DeleteMapping("/users/{userId}")
    fun deleteUser(@PathVariable userId: String, request: HttpServletRequest): MessageResponse =
        service.deleteUser(userId, request)

    @PostMapping("/users/{userId}/sessions/revoke")
    fun revokeSessions(@PathVariable userId: String, request: HttpServletRequest): MessageResponse =
        service.revokeUserSessions(userId, request)

    @PostMapping("/users/{userId}/mfa/reset")
    fun resetMfa(@PathVariable userId: String, request: HttpServletRequest): MessageResponse =
        service.resetMfa(userId, request)

    @GetMapping("/oauth-clients")
    fun clients(request: HttpServletRequest) = service.listClients(request)

    @PostMapping("/oauth-clients")
    fun createClient(
        @Valid @RequestBody body: OAuthClientCreate,
        request: HttpServletRequest,
    ) = service.createClient(body, request)

    @PatchMapping("/oauth-clients/{clientId}")
    fun updateClient(
        @PathVariable clientId: String,
        @RequestBody body: OAuthClientUpdate,
        request: HttpServletRequest,
    ) = service.updateClient(clientId, body, request)

    @PostMapping("/oauth-clients/{clientId}/rotate-secret")
    fun rotateSecret(@PathVariable clientId: String, request: HttpServletRequest) =
        service.rotateSecret(clientId, request)

    @DeleteMapping("/oauth-clients/{clientId}")
    fun deleteClient(@PathVariable clientId: String, request: HttpServletRequest): MessageResponse =
        service.deleteClient(clientId, request)

    @GetMapping("/organizations")
    fun organizations(request: HttpServletRequest) = service.listOrganizations(request)

    @DeleteMapping("/organizations/{organizationId}")
    fun deleteOrganization(
        @PathVariable organizationId: String,
        request: HttpServletRequest,
    ): MessageResponse = service.deleteOrganization(organizationId, request)

    @GetMapping("/sessions")
    fun sessions(request: HttpServletRequest, @RequestParam(required = false) userId: String?) =
        service.listSessions(request, userId)

    @GetMapping("/audit")
    fun audit(
        request: HttpServletRequest,
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = service.audit(request, eventType, actorId, limit.coerceIn(1, 1000))

    @GetMapping("/security-policies")
    fun policies(request: HttpServletRequest) = service.policies(request)

    @PutMapping("/security-policies/{key}")
    fun updatePolicy(
        @PathVariable key: String,
        @RequestBody body: SecurityPolicyUpdate,
        request: HttpServletRequest,
    ) = service.updatePolicy(key, body, request)
}
