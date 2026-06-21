package uz.ods.sso.tenant

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.TenantEntity
import uz.ods.sso.persistence.TenantRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository

object TenantContext {
    val current: ScopedValue<String> = ScopedValue.newInstance()

    fun slug(): String = current.orElse("default")
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter(
    private val properties: OdsProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val slug = request.getHeader("X-Tenant-Slug")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]{0,62}")) }
            ?: properties.defaultTenant
        ScopedValue.where(TenantContext.current, slug).run {
            filterChain.doFilter(request, response)
        }
    }
}

@Component
class TenantService(
    private val tenants: TenantRepository,
) {
    fun current(): TenantEntity = tenants.findBySlug(TenantContext.slug())
        ?: error("Tenant '${TenantContext.slug()}' has not been provisioned")
}

class TenantAwareRegisteredClientRepository(
    private val delegate: RegisteredClientRepository,
    private val tenants: TenantRepository,
) : RegisteredClientRepository {
    override fun save(registeredClient: RegisteredClient) = delegate.save(registeredClient)

    override fun findById(id: String): RegisteredClient? = visible(delegate.findById(id))

    override fun findByClientId(clientId: String): RegisteredClient? =
        visible(delegate.findByClientId(clientId))

    fun findByIdIncludingDisabled(id: String): RegisteredClient? =
        tenantVisible(delegate.findById(id))

    fun findByClientIdIncludingDisabled(clientId: String): RegisteredClient? =
        tenantVisible(delegate.findByClientId(clientId))

    private fun visible(client: RegisteredClient?): RegisteredClient? {
        if (client == null) return null
        val enabled = client.clientSettings.settings["enabled"] as? Boolean ?: true
        return tenantVisible(client)?.takeIf { enabled }
    }

    private fun tenantVisible(client: RegisteredClient?): RegisteredClient? {
        if (client == null) return null
        val tenant = tenants.findBySlug(TenantContext.slug()) ?: return null
        val tenantId = client.clientSettings.settings["tenant_id"]?.toString()
        return client.takeIf { tenantId == null || tenantId == tenant.id }
    }
}
