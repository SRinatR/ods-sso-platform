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
}
