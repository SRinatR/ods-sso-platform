package uz.ods.sso.config

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.LogoutFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserConsentRepository
import uz.ods.sso.persistence.TenantRepository
import uz.ods.sso.persistence.UsedRefreshTokenRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.persistence.PartnerApplicationRepository
import uz.ods.sso.persistence.PartnerMembershipRepository
import uz.ods.sso.persistence.PartnerOrganizationRepository
import uz.ods.sso.consent.MirroringAuthorizationConsentService
import uz.ods.sso.tenant.TenantAwareRegisteredClientRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.audit.AuditService
import uz.ods.sso.oauth.RotationTrackingAuthorizationService
import uz.ods.sso.passkey.PasskeyAuthenticationSuccessHandler
import uz.ods.sso.session.SessionCookieAuthenticationFilter
import uz.ods.sso.shared.ApiErrorResponse
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.function.Consumer

@Configuration
class SecurityConfiguration(
    private val properties: OdsProperties,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    @Order(1)
    fun authorizationServerSecurityFilterChain(
        http: HttpSecurity,
        sessionFilter: SessionCookieAuthenticationFilter,
    ): SecurityFilterChain {
        val configurer = OAuth2AuthorizationServerConfigurer()
        configurer.oidc(withDefaults())
        configurer.authorizationEndpoint {
            it.consentPage("${properties.accountUrl}/consent")
            it.authenticationProviders { providers ->
                providers.filterIsInstance<OAuth2AuthorizationCodeRequestAuthenticationProvider>().forEach { provider ->
                    val requiredParameters = Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> { context ->
                        val authentication =
                            context.getAuthentication<OAuth2AuthorizationCodeRequestAuthenticationToken>()
                        val missingState = authentication.state.isNullOrBlank()
                        val missingNonce =
                            OidcScopes.OPENID in authentication.scopes &&
                                authentication.additionalParameters["nonce"]?.toString().isNullOrBlank()
                        val missingCodeChallenge =
                            authentication.additionalParameters["code_challenge"]?.toString().isNullOrBlank()
                        val invalidCodeChallengeMethod =
                            authentication.additionalParameters["code_challenge_method"]?.toString() != "S256"
                        if (missingState || missingNonce || missingCodeChallenge || invalidCodeChallengeMethod) {
                            val (description, documentationUri) = when {
                                missingState -> "state is required" to
                                    "https://datatracker.ietf.org/doc/html/rfc6749#section-10.12"
                                missingNonce -> "nonce is required" to
                                    "https://openid.net/specs/openid-connect-core-1_0.html"
                                missingCodeChallenge -> "PKCE code_challenge is required" to
                                    "https://datatracker.ietf.org/doc/html/rfc7636"
                                else -> "Only PKCE S256 is supported" to
                                    "https://datatracker.ietf.org/doc/html/rfc7636#section-4.3"
                            }
                            throw OAuth2AuthorizationCodeRequestAuthenticationException(
                                OAuth2Error(
                                    OAuth2ErrorCodes.INVALID_REQUEST,
                                    description,
                                    documentationUri,
                                ),
                                authentication,
                            )
                        }
                    }
                    provider.setAuthenticationValidator(
                        OAuth2AuthorizationCodeRequestAuthenticationValidator().andThen(requiredParameters),
                    )
                }
            }
        }
        val endpointsMatcher: RequestMatcher = configurer.endpointsMatcher

        http
            .securityMatcher(endpointsMatcher)
            .with(configurer) { }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(accountLoginEntryPoint())
            }
            .addFilterAfter(sessionFilter, LogoutFilter::class.java)
            .cors(withDefaults())
            .csrf { csrf -> csrf.ignoringRequestMatchers(endpointsMatcher) }
            .securityContext {
                it.securityContextRepository(RequestAttributeSecurityContextRepository())
            }
            .requestCache { it.disable() }

        return http.build()
    }

    @Bean
    @Order(2)
    fun applicationSecurityFilterChain(
        http: HttpSecurity,
        sessionFilter: SessionCookieAuthenticationFilter,
        passkeySuccessHandler: PasskeyAuthenticationSuccessHandler,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(
                        "/health",
                        "/ready",
                        "/error",
                        "/privacy",
                        "/docs",
                        "/docs/**",
                        "/swagger-ui/**",
                        "/openapi.json",
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/api/v1/auth/login",
                        "/api/v1/auth/mfa/verify",
                        "/api/v1/public/status",
                        "/webauthn/authenticate/options",
                        "/login/webauthn",
                        "/api/v1/dev/mailbox",
                        "/internal/caddy/allow-domain",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter::class.java)
            .webAuthn {
                it.rpId(properties.rootDomain)
                    .rpName("ODS Identity")
                    .allowedOrigins(passkeyOrigins())
                    .disableDefaultRegistrationPage(true)
            }
            .cors(withDefaults())
            .csrf { it.disable() }
            .securityContext {
                it.securityContextRepository(RequestAttributeSecurityContextRepository())
            }
            .requestCache { it.disable() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, _ ->
                    writeUnauthorized(request, response)
                }
            }
        val chain = http.build()
        chain.filters.filterIsInstance<WebAuthnAuthenticationFilter>().forEach {
            it.setAuthenticationSuccessHandler(passkeySuccessHandler)
        }
        return chain
    }

    @Bean
    fun disableSessionFilterServletRegistration(
        filter: SessionCookieAuthenticationFilter,
    ): FilterRegistrationBean<SessionCookieAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun registeredClientRepository(
        jdbc: JdbcOperations,
        tenants: TenantRepository,
    ): RegisteredClientRepository = TenantAwareRegisteredClientRepository(
        JdbcRegisteredClientRepository(jdbc),
        tenants,
    )

    @Bean
    fun passkeyUserDetailsService(users: UserRepository): UserDetailsService = UserDetailsService { userId ->
        val user = users.findByPublicId(userId) ?: throw UsernameNotFoundException("User was not found")
        User.withUsername(user.id)
            .password(user.passwordHash)
            .roles(user.role.uppercase())
            .disabled(user.status != "active")
            .build()
    }

    @Bean
    fun passkeyUserEntityRepository(jdbc: JdbcOperations) =
        JdbcPublicKeyCredentialUserEntityRepository(jdbc)

    @Bean
    fun passkeyCredentialRepository(jdbc: JdbcOperations) =
        JdbcUserCredentialRepository(jdbc)

    @Bean
    fun authorizationService(
        jdbc: JdbcOperations,
        clients: RegisteredClientRepository,
        usedTokens: UsedRefreshTokenRepository,
        users: UserRepository,
        sessions: UserSessionRepository,
        crypto: CryptoService,
        audit: AuditService,
    ): OAuth2AuthorizationService {
        val service = JdbcOAuth2AuthorizationService(jdbc, clients)
        return RotationTrackingAuthorizationService(
            service,
            clients,
            usedTokens,
            users,
            sessions,
            crypto,
            audit,
        )
    }

    @Bean
    fun authorizationConsentService(
        jdbc: JdbcOperations,
        clients: RegisteredClientRepository,
        consents: UserConsentRepository,
        users: UserRepository,
        audit: AuditService,
    ): OAuth2AuthorizationConsentService = MirroringAuthorizationConsentService(
        JdbcOAuth2AuthorizationConsentService(jdbc, clients),
        consents,
        users,
        clients,
        audit,
    )

    @Bean
    fun authorizationServerSettings(): AuthorizationServerSettings =
        AuthorizationServerSettings.builder()
            .issuer(properties.issuer.trimEnd('/'))
            .authorizationEndpoint("/authorize")
            .tokenEndpoint("/token")
            .jwkSetEndpoint("/.well-known/jwks.json")
            .tokenRevocationEndpoint("/revoke")
            .tokenIntrospectionEndpoint("/introspect")
            .oidcUserInfoEndpoint("/userinfo")
            .build()

    @Bean
    fun jwkSource(): JWKSource<SecurityContext> {
        val (privateKey, publicKey) = rsaKeys()
        val rsaKey = RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(properties.jwtKeyId)
            .build()
        val jwkSet = JWKSet(rsaKey)
        return JWKSource { selector, _ -> selector.select(jwkSet) }
    }

    @Bean
    fun jwtDecoder(jwkSource: JWKSource<SecurityContext>): JwtDecoder =
        OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)

    @Bean
    fun tokenCustomizer(
        users: UserRepository,
        partnerApplications: PartnerApplicationRepository,
        partnerMemberships: PartnerMembershipRepository,
        partnerOrganizations: PartnerOrganizationRepository,
    ): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            if (context.tokenType == OAuth2TokenType.ACCESS_TOKEN || context.tokenType.value == "id_token") {
                val principal = context.getPrincipal<Authentication>()
                val principalName = context.authorization?.principalName ?: principal?.name ?: return@OAuth2TokenCustomizer
                val user = users.findByPublicId(principalName) ?: return@OAuth2TokenCustomizer
                val scopes = context.authorizedScopes
                context.claims.claim("tenant_id", user.tenantId)
                context.claims.claim("role", user.role)
                context.claims.claim("email_verified", user.emailVerified)
                if (OidcScopes.EMAIL in scopes) context.claims.claim("email", user.email)
                val name = user.name
                if (OidcScopes.PROFILE in scopes && name != null) context.claims.claim("name", name)
                val partnerApplication = partnerApplications.findByRegisteredClientId(context.registeredClient.id)
                if (partnerApplication != null) {
                    val organization = partnerOrganizations.findByPublicId(partnerApplication.organizationId)
                    val membership = partnerMemberships.findByOrganizationIdAndUserId(
                        partnerApplication.organizationId,
                        user.id,
                    )?.takeIf { it.status == "active" }
                    if (organization != null && organization.status == "active" && membership != null) {
                        context.claims.claim("organization_id", organization.id)
                        context.claims.claim("organization_slug", organization.slug)
                        context.claims.claim("organization_role", membership.role)
                        context.claims.claim("roles", listOf(membership.role))
                        context.claims.claim("permissions", partnerPermissionsForRole(membership.role))
                    }
                }
                val authorities = principal?.authorities.orEmpty().map { it.authority }.toSet()
                val amr = when {
                    "AMR_WEBAUTHN" in authorities -> listOf("webauthn")
                    "AMR_OTP" in authorities -> listOf("pwd", "otp")
                    else -> listOf("pwd")
                }
                context.claims.claim("amr", amr)
                context.claims.claim("acr", if (amr == listOf("pwd")) "urn:ods:loa:1" else "urn:ods:loa:2")
            }
        }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.corsOrigins
            allowedOriginPatterns = properties.corsOriginPatterns
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-ID", "X-Tenant-Slug")
            allowCredentials = true
            maxAge = Duration.ofHours(1).seconds
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    private fun passkeyOrigins(): Set<String> {
        val configured = properties.corsOrigins.filter { it.startsWith("https://") }
        val fixed = if (properties.productionLike) {
            listOf(
                "https://auth.${properties.rootDomain}",
                "https://accounts.${properties.rootDomain}",
                "https://partners.${properties.rootDomain}",
                "https://admin.${properties.rootDomain}",
            )
        } else {
            properties.corsOrigins
        }
        return (configured + fixed).toSet()
    }

    private fun accountLoginEntryPoint() = AuthenticationEntryPoint { request, response, _ ->
        val returnTo = request.requestURL.toString() +
            request.queryString?.let { "?$it" }.orEmpty()
        response.sendRedirect(
            "${properties.accountUrl}/login?return_to=" +
                URLEncoder.encode(returnTo, StandardCharsets.UTF_8),
        )
    }

    private fun writeUnauthorized(request: HttpServletRequest, response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(
            response.writer,
            ApiErrorResponse.from(
                request,
                HttpStatus.UNAUTHORIZED,
                "not_authenticated",
                "Authentication is required",
            ),
        )
    }

    private fun rsaKeys(): Pair<RSAPrivateKey, RSAPublicKey> {
        if (properties.jwtPrivateKey.isNotBlank() && properties.jwtPublicKey.isNotBlank()) {
            val factory = KeyFactory.getInstance("RSA")
            val privateBytes = decodePem(properties.jwtPrivateKey, "PRIVATE KEY")
            val publicBytes = decodePem(properties.jwtPublicKey, "PUBLIC KEY")
            return factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)) as RSAPrivateKey to
                (factory.generatePublic(X509EncodedKeySpec(publicBytes)) as RSAPublicKey)
        }
        check(!properties.productionLike) { "JWT_PRIVATE_KEY and JWT_PUBLIC_KEY are required outside development" }
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val pair = generator.generateKeyPair()
        return pair.private as RSAPrivateKey to pair.public as RSAPublicKey
    }

    private fun decodePem(value: String, type: String): ByteArray = Base64.getMimeDecoder().decode(
        value.replace("\\n", "\n")
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace(Regex("\\s"), ""),
    )
}

internal fun partnerPermissionsForRole(role: String): List<String> = when (role) {
    "owner" -> listOf(
        "organization:manage",
        "members:manage",
        "applications:manage",
        "content:read",
        "content:write",
    )
    "admin" -> listOf("members:manage", "applications:manage", "content:read", "content:write")
    "editor" -> listOf("content:read", "content:write")
    "user" -> listOf("content:read", "content:use")
    else -> listOf("content:read")
}
