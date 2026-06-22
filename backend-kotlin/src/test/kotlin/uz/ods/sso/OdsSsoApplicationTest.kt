package uz.ods.sso

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class OdsSsoApplicationTest {
    @Test
    fun `runtime classes are initialized before concurrent request handling`() {
        assertThatCode { prewarmRuntimeClasses() }.doesNotThrowAnyException()
    }
}
