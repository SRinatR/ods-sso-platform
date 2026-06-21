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
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.core.Authentication
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
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import uz.ods.sso.persistence.UserRepository
import uz.ods.sso.persistence.UserConsentRepository
import uz.ods.sso.persistence.TenantRepository
import uz.ods.sso.persistence.UsedRefreshTokenRepository
import uz.ods.sso.persistence.UserSessionRepository
import uz.ods.sso.consent.MirroringAuthorizationConsentService
import uz.ods.sso.tenant.TenantAwareRegisteredClientRepository
import uz.ods.sso.security.CryptoService
import uz.ods.sso.audit.AuditService
import uz.ods.sso.oauth.RotationTrackingAuthorizationService
import uz.ods.sso.session.SessionCookieAuthenticationFilter
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
                        if (missingState || missingNonce) {
                            val parameter = if (missingState) "state" else "nonce"
                            throw OAuth2AuthorizationCodeRequestAuthenticationException(
                                OAuth2Error(
                                    OAuth2ErrorCodes.INVALID_REQUEST,
                                    "$parameter is required",
                                    "https://openid.net/specs/openid-connect-core-1_0.html",
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
            .addFilterBefore(sessionFilter, OAuth2AuthorizationEndpointFilter::class.java)
            .cors(withDefaults())
            .csrf { csrf -> csrf.ignoringRequestMatchers(endpointsMatcher) }

        return http.build()
    }

    @Bean
    @Order(2)
    fun applicationSecurityFilterChain(
        http: HttpSecurity,
        sessionFilter: SessionCookieAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(
                        "/health",
                        "/ready",
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
                        "/api/v1/dev/mailbox",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter::class.java)
            .cors(withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.disable() }
            .requestCache { it.disable() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, _ ->
                    writeUnauthorized(request, response)
                }
            }
        return http.build()
    }

    @Bean
    fun registeredClientRepository(
        jdbc: JdbcOperations,
        tenants: TenantRepository,
    ): RegisteredClientRepository = TenantAwareRegisteredClientRepository(
        JdbcRegisteredClientRepository(jdbc),
        tenants,
    )

    @Bean
    fun authorizationService(
        jdbc: JdbcOperations,
        clients: RegisteredClientRepository,
        usedTokens: UsedRefreshTokenRepository,
        users: UserRepository,
        sessions: UserSessionRepository,
        crypto: CryptoService,
        audit: AuditService,
    ): OAuth2AuthorizationService = RotationTrackingAuthorizationService(
        JdbcOAuth2AuthorizationService(jdbc, clients),
        usedTokens,
        users,
        sessions,
        crypto,
        audit,
    )

    @Bean
    fun authorizationConsentService(
        jdbc: JdbcOperations,
        clients: RegisteredClientRepository,
        consents: UserConsentRepository,
        users: UserRepository,
    ): OAuth2AuthorizationConsentService = MirroringAuthorizationConsentService(
        JdbcOAuth2AuthorizationConsentService(jdbc, clients),
        consents,
        users,
        clients,
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
    fun tokenCustomizer(users: UserRepository): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            if (context.tokenType == OAuth2TokenType.ACCESS_TOKEN || context.tokenType.value == "id_token") {
                val principal = context.getPrincipal<Authentication>()
                val principalName = context.authorization?.principalName ?: principal?.name ?: return@OAuth2TokenCustomizer
                val user = users.findById(principalName).orElse(null) ?: return@OAuth2TokenCustomizer
                val scopes = context.authorizedScopes
                context.claims.claim("tenant_id", user.tenantId)
                context.claims.claim("role", user.role)
                context.claims.claim("email_verified", user.emailVerified)
                if (OidcScopes.EMAIL in scopes) context.claims.claim("email", user.email)
                val name = user.name
                if (OidcScopes.PROFILE in scopes && name != null) context.claims.claim("name", name)
                val mfa = principal?.authorities.orEmpty().any { it.authority == "AMR_OTP" }
                context.claims.claim("amr", if (mfa) listOf("pwd", "otp") else listOf("pwd"))
                context.claims.claim("acr", if (mfa) "urn:ods:loa:2" else "urn:ods:loa:1")
            }
        }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.corsOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-ID", "X-Tenant-Slug")
            allowCredentials = true
            maxAge = Duration.ofHours(1).seconds
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
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
        response.contentType = "application/json"
        val requestId = request.getAttribute(uz.ods.sso.shared.RequestContextFilter.REQUEST_ID)?.toString() ?: "unknown"
        response.writer.write(
            """{"error":"not_authenticated","message":"Authentication is required","details":[],"request_id":"$requestId"}""",
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
