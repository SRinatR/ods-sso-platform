package uz.ods.sso.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

class HttpClientConfigurationTest {
    @Test
    fun `critical client explicitly opts into HTTP3 and virtual threads`() {
        val configuration = HttpClientConfiguration()
        configuration.externalHttpExecutor().use { executor ->
            val client = configuration.criticalExternalHttpClient(executor)

            assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_3)
            assertThat(executor.submit<Boolean> { Thread.currentThread().isVirtual }.get()).isTrue()
        }
    }
}
