package uz.ods.sso.tenant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TenantContextTest {
    @Test
    fun `scoped tenant does not leak after request scope`() {
        assertThat(TenantContext.slug()).isEqualTo("default")

        ScopedValue.where(TenantContext.current, "university-a").run {
            assertThat(TenantContext.slug()).isEqualTo("university-a")
        }

        assertThat(TenantContext.slug()).isEqualTo("default")
    }
}
