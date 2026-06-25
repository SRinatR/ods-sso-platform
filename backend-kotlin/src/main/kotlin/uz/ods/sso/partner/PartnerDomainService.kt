package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.shared.AppException
import java.net.URI

@Service
class PartnerDomainService(
    private val organizations: PartnerOrganizationRepository,
    private val properties: OdsProperties,
) {
    fun requestedSlug(request: HttpServletRequest): String? =
        candidateHosts(request).firstNotNullOfOrNull(::slugFromDomain)

    fun portalUrl(slug: String): String = properties.partnerPortalUrl(slug)

    fun requireAvailableSlug(value: String): String {
        val slug = value.trim().lowercase()
        if (!SLUG.matches(slug)) {
            throw AppException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "partner_slug_invalid",
                "Organization code must contain 3-63 lowercase letters, digits or hyphens",
            )
        }
        if (slug in RESERVED) {
            throw AppException(HttpStatus.CONFLICT, "partner_slug_reserved", "This organization code is reserved")
        }
        if (organizations.findBySlug(slug) != null) {
            throw AppException(HttpStatus.CONFLICT, "partner_slug_taken", "This organization code is already in use")
        }
        return slug
    }

    fun deriveSlug(websiteUrl: String?): String? {
        val value = websiteUrl?.trim()?.ifBlank { null } ?: return null
        val normalized = if (SCHEME.containsMatchIn(value)) value else "https://$value"
        val host = runCatching { URI(normalized).host }.getOrNull()
            ?.trimEnd('.')
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return null
        return host.substringBefore('.').takeIf(SLUG::matches)
    }

    fun normalizeWebsite(websiteUrl: String?): String? {
        val value = websiteUrl?.trim()?.ifBlank { null } ?: return null
        val normalized = if (SCHEME.containsMatchIn(value)) value else "https://$value"
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            throw AppException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Website URL is invalid")
        }
        return uri.toString()
    }

    fun domainAllowed(domain: String): Boolean {
        val slug = slugFromDomain(domain) ?: return false
        return organizations.findBySlugAndStatus(slug, "active") != null
    }

    private fun slugFromDomain(rawDomain: String): String? {
        val domain = rawDomain.trim().lowercase().trimEnd('.').substringBefore(':')
        val root = properties.rootDomain.trim().lowercase().trimEnd('.')
        if (!domain.endsWith(".$root")) return null
        val slug = domain.removeSuffix(".$root")
        if (slug.contains('.') || slug in RESERVED || !SLUG.matches(slug)) return null
        return slug
    }

    private fun candidateHosts(request: HttpServletRequest): List<String> {
        val forwardedHost = request.getHeader("X-Forwarded-Host")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
        val forwarded = request.getHeader("Forwarded")
            ?.split(";")
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("host=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"')
        return listOfNotNull(
            request.getHeader("X-ODS-Portal-Host")?.trim(),
            forwardedHost,
            forwarded,
            request.serverName,
        ).filter(String::isNotBlank).distinct()
    }

    companion object {
        private val SLUG = Regex("^[a-z0-9][a-z0-9-]{2,62}$")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private val RESERVED = setOf(
            "www",
            "auth",
            "accounts",
            "partners",
            "admin",
            "api",
            "docs",
            "status",
            "sso",
            "scim",
            "webhooks",
        )
    }
}

@RestController
@RequestMapping("/internal/caddy")
class CaddyDomainController(
    private val domains: PartnerDomainService,
) {
    @GetMapping("/allow-domain")
    fun allowDomain(@RequestParam domain: String): ResponseEntity<Void> =
        if (domains.domainAllowed(domain)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
}
