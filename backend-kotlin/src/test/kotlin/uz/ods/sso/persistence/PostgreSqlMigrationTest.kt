package uz.ods.sso.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationTest {
    @Container
    private val postgres = PostgreSQLContainer("postgres:18.4-alpine")

    @Test
    fun `Flyway produces UUIDv7 primary keys and stable public identifiers`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val domainTables = listOf(
                "tenants",
                "users",
                "user_sessions",
                "account_tokens",
                "mfa_methods",
                "backup_codes",
                "login_history",
                "audit_logs",
                "user_consents",
                "security_policies",
                "trusted_devices",
                "risk_assessments",
                "domain_outbox",
                "used_refresh_tokens",
                "federation_providers",
                "key_metadata",
                "partner_organizations",
                "partner_memberships",
                "partner_applications",
            )

            domainTables.forEach { table ->
                val columns = connection.metaData.getColumns(null, "public", table, null).use { result ->
                    buildMap {
                        while (result.next()) put(result.getString("COLUMN_NAME"), result.getString("TYPE_NAME"))
                    }
                }
                assertThat(columns).containsKeys("internal_id", "public_id")
                assertThat(columns.getValue("internal_id")).isEqualTo("uuid")

                val primaryKeys = connection.metaData.getPrimaryKeys(null, "public", table).use { result ->
                    buildList {
                        while (result.next()) add(result.getString("COLUMN_NAME"))
                    }
                }
                assertThat(primaryKeys).containsExactly("internal_id")
            }

            connection.createStatement().use { statement ->
                statement.executeQuery("select uuid_extract_version(uuidv7())").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt(1)).isEqualTo(7)
                }
            }
        }
    }

    @Test
    fun `V10 adds email scope to existing OIDC clients`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("9")
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into oauth2_registered_client (
                        id,
                        client_id,
                        client_name,
                        client_authentication_methods,
                        authorization_grant_types,
                        redirect_uris,
                        scopes,
                        client_settings,
                        token_settings
                    ) values (
                        'legacy-client',
                        'cli_legacy',
                        'Legacy client',
                        'client_secret_basic',
                        'authorization_code',
                        'https://api.umo.uz/api/v1/auth/sso/callback',
                        'openid',
                        '{}',
                        '{}'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into oauth2_registered_client (
                        id,
                        client_id,
                        client_name,
                        client_authentication_methods,
                        authorization_grant_types,
                        redirect_uris,
                        scopes,
                        client_settings,
                        token_settings
                    ) values (
                        'scoped-client',
                        'cli_scoped',
                        'Scoped client',
                        'client_secret_basic',
                        'authorization_code',
                        'https://api.umo.uz/api/v1/auth/sso/callback',
                        'openid,profile,email',
                        '{}',
                        '{}'
                    )
                    """.trimIndent(),
                )
            }
        }

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "select client_id, scopes from oauth2_registered_client where client_id in (?, ?) order by client_id",
            ).use { statement ->
                statement.setString(1, "cli_legacy")
                statement.setString(2, "cli_scoped")
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("client_id")).isEqualTo("cli_legacy")
                    assertThat(result.getString("scopes")).isEqualTo("openid,email,full_name_cyrillic,full_name_latin")

                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("client_id")).isEqualTo("cli_scoped")
                    assertThat(result.getString("scopes")).isEqualTo(
                        "openid,profile,email,full_name_cyrillic,full_name_latin",
                    )

                    assertThat(result.next()).isFalse()
                }
            }
        }
    }
}
