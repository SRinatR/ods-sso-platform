package uz.ods.sso

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import uz.ods.sso.persistence.PartnerApplicationRepository
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.persistence.UserRepository

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ods;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.defer-datasource-initialization=true",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.data.redis.connect-timeout=20ms",
        "spring.data.redis.timeout=20ms",
        "ods.environment=test",
        "ods.issuer=http://localhost",
        "ods.account-url=http://localhost",
        "ods.api-url=http://localhost",
        "ods.require-email-verification=false",
        "ods.bootstrap-admin-email=",
        "ods.bootstrap-admin-password=",
        "ods.kafka-events-enabled=false",
        "management.endpoints.enabled-by-default=false",
    ],
)
class PilotFlowIntegrationTest {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var organizations: PartnerOrganizationRepository

    @Autowired
    private lateinit var applications: PartnerApplicationRepository

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    @Test
    fun `pilot registration login and partner provisioning flow`() {
        val email = "pilot-${System.nanoTime()}@example.com"
        val password = "A-strong-pilot-password-123!"
        val organizationSlug = "pilot-${System.nanoTime()}"

        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to email,
                            "password" to password,
                            "name" to "Pilot User",
                            "accept_terms" to true,
                        ),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.ok").value(true))

        val user = users.findByTenantIdAndEmailIgnoreCase("tnt_missing", email)
        assertThat(user).isNull()
        val storedUser = users.findAll().single { it.email == email }
        assertThat(storedUser.internalId.version()).isEqualTo(7)
        assertThat(storedUser.id).startsWith("usr_")

        val login = mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )
            .andExpect(status().isOk)
            .andExpect(cookie().exists("ods_session"))
            .andExpect(jsonPath("$.user_id").value(storedUser.id))
            .andReturn()

        val sessionCookie: Cookie = login.response.getCookie("ods_session")!!

        mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(storedUser.id))
            .andExpect(jsonPath("$.email").value(email))

        mvc.perform(get("/api/v1/partner/workspace").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organization").doesNotExist())
            .andExpect(jsonPath("$.integration.issuer").value("http://localhost"))

        mvc.perform(
            post("/api/v1/partner/organizations")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Pilot Partner",
                            "slug" to organizationSlug,
                            "website_url" to "https://partner.example",
                            "contact_email" to email,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organization.id").value(org.hamcrest.Matchers.startsWith("org_")))
            .andExpect(jsonPath("$.organization.portal_url").value("https://$organizationSlug.localhost"))

        val organization = organizations.findAll().single()
        assertThat(organization.internalId.version()).isEqualTo(7)

        mvc.perform(
            get("/api/v1/partner/workspace")
                .cookie(sessionCookie)
                .with { it.serverName = "$organizationSlug.localhost"; it },
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organization.slug").value(organizationSlug))

        val secondOrganizationSlug = "second-${System.nanoTime()}"
        mvc.perform(
            post("/api/v1/partner/organizations")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Second Partner",
                            "slug" to secondOrganizationSlug,
                            "contact_email" to email,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organization.slug").value(secondOrganizationSlug))
            .andExpect(jsonPath("$.organizations.length()").value(2))

        mvc.perform(get("/api/v1/partner/workspace").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organization").doesNotExist())
            .andExpect(jsonPath("$.organizations.length()").value(2))

        mvc.perform(get("/internal/caddy/allow-domain").param("domain", "$organizationSlug.localhost"))
            .andExpect(status().isNoContent)
        mvc.perform(get("/internal/caddy/allow-domain").param("domain", "unknown.localhost"))
            .andExpect(status().isNotFound)

        mvc.perform(
            post("/api/v1/partner/applications")
                .cookie(sessionCookie)
                .with { it.serverName = "$organizationSlug.localhost"; it }
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Pilot Application",
                            "description" to "Integration test",
                            "redirect_uris" to listOf("https://partner.example/sso/callback"),
                            "post_logout_redirect_uris" to listOf("https://partner.example/"),
                            "scopes" to listOf("openid", "profile", "email", "offline_access"),
                            "client_type" to "confidential",
                            "token_endpoint_auth_method" to "client_secret_basic",
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("appmeta_")))
            .andExpect(jsonPath("$.client_id").value(org.hamcrest.Matchers.startsWith("cli_")))
            .andExpect(jsonPath("$.client_secret").isNotEmpty)
            .andExpect(jsonPath("$.post_logout_redirect_uris[0]").value("https://partner.example/"))
            .andExpect(jsonPath("$.client_type").value("confidential"))
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("client_secret_basic"))
            .andExpect(jsonPath("$.require_pkce").value(true))

        assertThat(applications.findAll()).hasSize(1)

        val memberEmail = "member-${System.nanoTime()}@example.com"
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "email" to memberEmail,
                            "password" to password,
                            "accept_terms" to true,
                        ),
                    ),
                ),
        ).andExpect(status().isCreated)

        val createdMember = mvc.perform(
            post("/api/v1/partner/members")
                .cookie(sessionCookie)
                .with { it.serverName = "$organizationSlug.localhost"; it }
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to memberEmail, "role" to "editor"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(memberEmail))
            .andExpect(jsonPath("$.role").value("editor"))
            .andReturn()

        val membershipId = objectMapper.readTree(createdMember.response.contentAsString)["id"].asText()
        mvc.perform(
            patch("/api/v1/partner/members/$membershipId")
                .cookie(sessionCookie)
                .with { it.serverName = "$organizationSlug.localhost"; it }
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("role" to "viewer", "status" to "disabled"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("viewer"))
            .andExpect(jsonPath("$.status").value("disabled"))

        mvc.perform(get("/api/v1/account/sessions").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.startsWith("ses_")))

        mvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(cookie().maxAge("ods_session", 0))
    }

    @Test
    fun `public endpoints validate requests and protected endpoints reject anonymous access`() {
        mvc.perform(get("/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))

        mvc.perform(get("/privacy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("published"))

        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"invalid","password":"short","name":"","accept_terms":false}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnprocessableContent)

        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect { assertThat(it.response.characterEncoding).isEqualTo("UTF-8") }
            .andExpect(jsonPath("$.type").value("urn:ods:problem:not_authenticated"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Authentication is required"))
            .andExpect(jsonPath("$.error").value("not_authenticated"))
    }
}
