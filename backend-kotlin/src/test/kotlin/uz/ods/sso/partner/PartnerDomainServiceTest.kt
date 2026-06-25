package uz.ods.sso.partner

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.PartnerOrganizationEntity
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.shared.AppException

class PartnerDomainServiceTest {
    private val organizations = mock<PartnerOrganizationRepository>()
    private val properties = OdsProperties(
        environment = "test",
        rootDomain = "ods.uz",
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val service = PartnerDomainService(organizations, properties)

    @Test
    fun `tenant domain is accepted only for an active organization`() {
        val organization = PartnerOrganizationEntity(slug = "company", status = "active")
        whenever(organizations.findBySlugAndStatus("company", "active")).thenReturn(organization)
        val request = mock<HttpServletRequest>()
        whenever(request.serverName).thenReturn("company.ods.uz")

        assertThat(service.requestedSlug(request)).isEqualTo("company")
        assertThat(service.domainAllowed("company.ods.uz")).isTrue()
        assertThat(service.domainAllowed("unknown.ods.uz")).isFalse()
        assertThat(service.domainAllowed("admin.ods.uz")).isFalse()
        assertThat(service.domainAllowed("nested.company.ods.uz")).isFalse()
    }

    @Test
    fun `tenant slug can be resolved from forwarded host`() {
        val request = mock<HttpServletRequest>()
        whenever(request.serverName).thenReturn("backend")
        whenever(request.getHeader("X-Forwarded-Host")).thenReturn("company.ods.uz")

        assertThat(service.requestedSlug(request)).isEqualTo("company")
    }

    @Test
    fun `website is normalized and supplies a default slug`() {
        assertThat(service.normalizeWebsite("www.company.uz")).isEqualTo("https://www.company.uz")
        assertThat(service.deriveSlug("https://www.company.uz")).isEqualTo("company")
        assertThat(service.portalUrl("company")).isEqualTo("https://company.ods.uz")
    }

    @Test
    fun `reserved and occupied slugs are rejected`() {
        assertThatThrownBy { service.requireAvailableSlug("admin") }
            .isInstanceOf(AppException::class.java)
            .hasMessage("This organization code is reserved")

        whenever(organizations.findBySlug("company")).thenReturn(PartnerOrganizationEntity(slug = "company"))
        assertThatThrownBy { service.requireAvailableSlug("company") }
            .isInstanceOf(AppException::class.java)
            .hasMessage("This organization code is already in use")
    }
}
