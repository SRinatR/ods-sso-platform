package uz.ods.sso.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.ApplicationArguments
import tools.jackson.databind.json.JsonMapper
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.SecurityPolicyEntity
import uz.ods.sso.persistence.SecurityPolicyRepository
import uz.ods.sso.persistence.BackupCodeRepository
import uz.ods.sso.persistence.MfaMethodRepository
import uz.ods.sso.persistence.TenantEntity
import uz.ods.sso.persistence.TenantRepository
import uz.ods.sso.persistence.UserEntity
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.security.CryptoService

class BootstrapServiceTest {
    @Test
    fun `bootstrap creates tenant policies and administrator`() {
        val properties = OdsProperties(
            environment = "test",
            defaultTenant = "default",
            bootstrapAdminEmail = "admin@example.com",
            bootstrapAdminPassword = "admin-password-value",
            sessionSecret = "session-secret-that-is-longer-than-32-characters",
            tokenPepper = "token-pepper-that-is-independent-and-long",
        )
        val tenants = mock<TenantRepository>()
        val users = mock<UserRepository>()
        val policies = mock<SecurityPolicyRepository>()
        val mfaMethods = mock<MfaMethodRepository>()
        val backupCodes = mock<BackupCodeRepository>()
        val sessions = mock<UserSessionRepository>()
        val crypto = CryptoService(properties)
        whenever(tenants.findBySlug("default")).thenReturn(null)
        whenever(tenants.save(any<TenantEntity>())).thenAnswer {
            (it.arguments[0] as TenantEntity).apply { publicId = "tnt_1" }
        }
        whenever(policies.findByTenantIdAndKey(any(), any())).thenReturn(null)
        whenever(policies.save(any<SecurityPolicyEntity>())).thenAnswer { it.arguments[0] }
        whenever(users.findByTenantIdAndEmailIgnoreCase("tnt_1", "admin@example.com")).thenReturn(null)
        whenever(users.save(any<UserEntity>())).thenAnswer { it.arguments[0] }

        BootstrapService(
            properties,
            tenants,
            users,
            policies,
            mfaMethods,
            backupCodes,
            sessions,
            crypto,
            JsonMapper.builder().build(),
        ).run(mock<ApplicationArguments>())

        verify(policies, times(BootstrapService.defaultPolicies.size))
            .save(any<SecurityPolicyEntity>())
        val admin = argumentCaptor<UserEntity>()
        verify(users).save(admin.capture())
        assertThat(admin.firstValue.role).isEqualTo("admin")
        assertThat(admin.firstValue.emailVerified).isTrue()
    }
}
