package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/partner")
class PartnerController(
    private val service: PartnerService,
) {
    @GetMapping("/workspace")
    fun workspace(): PartnerWorkspaceResponse = service.workspace()

    @PostMapping("/organizations")
    fun createOrganization(
        @Valid @RequestBody body: PartnerOrganizationCreate,
        request: HttpServletRequest,
    ): PartnerWorkspaceResponse = service.createOrganization(body, request)

    @PostMapping("/applications")
    fun createApplication(
        @Valid @RequestBody body: PartnerApplicationCreate,
        request: HttpServletRequest,
    ): PartnerApplicationResponse = service.createApplication(body, request)

    @PatchMapping("/applications/{applicationId}")
    fun updateApplication(
        @PathVariable applicationId: String,
        @Valid @RequestBody body: PartnerApplicationUpdate,
        request: HttpServletRequest,
    ): PartnerApplicationResponse = service.updateApplication(applicationId, body, request)

    @PostMapping("/applications/{applicationId}/rotate-secret")
    fun rotateSecret(
        @PathVariable applicationId: String,
        request: HttpServletRequest,
    ): PartnerApplicationResponse = service.rotateSecret(applicationId, request)
}
