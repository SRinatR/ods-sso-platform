package uz.ods.sso.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EntityIdentityTest {
    @Test
    fun `all domain entities use UUIDv7 primary keys and prefixed public IDs`() {
        val entities = listOf(
            TenantEntity() to "tnt_",
            UserEntity() to "usr_",
            UserSessionEntity() to "ses_",
            AccountTokenEntity() to "tok_",
            MfaMethodEntity() to "mfa_",
            BackupCodeEntity() to "bkc_",
            LoginHistoryEntity() to "log_",
            AuditLogEntity() to "aud_",
            UserConsentEntity() to "cns_",
            SecurityPolicyEntity() to "pol_",
            TrustedDeviceEntity() to "dev_",
            RiskAssessmentEntity() to "rsk_",
            DomainOutboxEntity() to "evt_",
            UsedRefreshTokenEntity() to "urt_",
            FederationProviderEntity() to "idp_",
            KeyMetadataEntity() to "key_",
            PartnerOrganizationEntity() to "org_",
            PartnerMembershipEntity() to "mem_",
            PartnerApplicationEntity() to "appmeta_",
        )

        assertThat(entities).allSatisfy { (entity, prefix) ->
            assertThat(entity.internalId.version()).isEqualTo(7)
            assertThat(entity.id).isEqualTo(entity.publicId)
            assertThat(entity.publicId).startsWith(prefix)
        }
        assertThat(entities.map { it.first.internalId }).doesNotHaveDuplicates()
        assertThat(entities.map { it.first.publicId }).doesNotHaveDuplicates()
    }

    @Test
    fun `user email verification state is derived from verification timestamp`() {
        val user = UserEntity()

        assertThat(user.emailVerified).isFalse()
        user.emailVerifiedAt = java.time.Instant.now()
        assertThat(user.emailVerified).isTrue()
    }
}
