package uz.ods.sso.oauth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.security.CryptoService
import uz.ods.sso.shared.AppException

class OAuthClientProvisioningServiceTest {
    private val properties = OdsProperties(
        sessionSecret = "session-secret-that-is-longer-than-32-characters",
        tokenPepper = "token-pepper-that-is-independent-and-long",
    )
    private val clients = mock<RegisteredClientRepository>()
    private val crypto = CryptoService(properties)
    private val service = OAuthClientProvisioningService(clients, crypto, properties)

    @Test
    fun `client lifecycle preserves PKCE scopes tenant and one time secret`() {
        val created = service.createConfidential(
            "tnt_1",
            " Partner App ",
            " Description ",
            listOf("https://partner.example/callback", "https://partner.example/callback"),
        )

        assertThat(created.client.id).startsWith("app_")
        assertThat(created.client.clientId).startsWith("cli_")
        assertThat(created.client.clientName).isEqualTo("Partner App")
        assertThat(created.client.redirectUris).containsExactly("https://partner.example/callback")
        assertThat(created.client.clientSettings.isRequireProofKey).isTrue()
        assertThat(created.client.clientSettings.settings["tenant_id"]).isEqualTo("tnt_1")
        assertThat(created.rawSecret).isNotBlank()

        val updated = service.update(
            created.client,
            "Updated App",
            "Updated",
            listOf("http://localhost:3000/callback"),
            false,
        )
        assertThat(updated.clientName).isEqualTo("Updated App")
        assertThat(updated.redirectUris).containsExactly("http://localhost:3000/callback")
        assertThat(service.isEnabled(updated)).isFalse()

        val rotated = service.rotateSecret(updated)
        assertThat(rotated.rawSecret).isNotEqualTo(created.rawSecret)
        assertThat(rotated.client.clientSecret).isNotEqualTo(created.client.clientSecret)
        verify(clients, times(3)).save(any<RegisteredClient>())
    }

    @Test
    fun `invalid names and redirect URIs fail closed`() {
        assertThatThrownBy {
            service.createConfidential("tnt_1", "x", null, listOf("https://partner.example/callback"))
        }.isInstanceOf(AppException::class.java)

        assertThatThrownBy {
            service.createConfidential("tnt_1", "Valid", null, emptyList())
        }.isInstanceOf(AppException::class.java)

        assertThatThrownBy {
            service.createConfidential("tnt_1", "Valid", null, listOf("http://remote.example/callback"))
        }.isInstanceOf(AppException::class.java)

        assertThatThrownBy {
            service.createConfidential("tnt_1", "Valid", null, listOf("https://partner.example/callback#fragment"))
        }.isInstanceOf(AppException::class.java)
    }

    @Test
    fun `lookup delegates to registered client repository`() {
        val client = service.createConfidential(
            "tnt_1",
            "Partner",
            null,
            listOf("https://partner.example/callback"),
        ).client
        whenever(clients.findById(client.id)).thenReturn(client)

        assertThat(service.findIncludingDisabledById(client.id)).isSameAs(client)
    }
}
