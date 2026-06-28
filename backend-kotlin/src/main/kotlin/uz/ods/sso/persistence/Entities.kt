package uz.ods.sso.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "tenants")
class TenantEntity(
    @Column(unique = true, nullable = false, length = 96)
    var slug: String = "",
    @Column(nullable = false, length = 255)
    var name: String = "",
    @Column(nullable = false, length = 24)
    var status: String = "active",
    @Column(nullable = false, columnDefinition = "text")
    var settingsJson: String = "{}",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) : IdentifiedEntity("tnt")

@Entity
@Table(
    name = "partner_organizations",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_partner_organizations_tenant_slug", columnNames = ["tenant_id", "slug"]),
        UniqueConstraint(name = "uq_partner_organizations_slug", columnNames = ["slug"]),
    ],
    indexes = [Index(name = "ix_partner_organizations_tenant_status", columnList = "tenant_id,status")],
)
class PartnerOrganizationEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(nullable = false, length = 96)
    var slug: String = "",
    @Column(nullable = false, length = 255)
    var name: String = "",
    @Column(name = "legal_name", length = 255)
    var legalName: String? = null,
    @Column(name = "website_url", length = 512)
    var websiteUrl: String? = null,
    @Column(name = "contact_email", nullable = false, length = 320)
    var contactEmail: String = "",
    @Column(nullable = false, length = 24)
    var status: String = "active",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) : IdentifiedEntity("org")

@Entity
@Table(
    name = "partner_memberships",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_partner_memberships_organization_user",
            columnNames = ["organization_id", "user_id"],
        ),
    ],
    indexes = [Index(name = "ix_partner_memberships_user_status", columnList = "user_id,status")],
)
class PartnerMembershipEntity(
    @Column(name = "organization_id", nullable = false, length = 40)
    var organizationId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false, length = 24)
    var role: String = "owner",
    @Column(nullable = false, length = 24)
    var status: String = "active",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("mem")

@Entity
@Table(
    name = "partner_applications",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_partner_applications_registered_client", columnNames = ["registered_client_id"]),
        UniqueConstraint(name = "uq_partner_applications_client_id", columnNames = ["client_id"]),
    ],
    indexes = [Index(name = "ix_partner_applications_organization_created", columnList = "organization_id,created_at")],
)
class PartnerApplicationEntity(
    @Column(name = "organization_id", nullable = false, length = 40)
    var organizationId: String = "",
    @Column(name = "registered_client_id", nullable = false, length = 100)
    var registeredClientId: String = "",
    @Column(name = "client_id", nullable = false, length = 100)
    var clientId: String = "",
    @Column(name = "created_by", nullable = false, length = 40)
    var createdBy: String = "",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) : IdentifiedEntity("appmeta")

@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(name = "uq_users_tenant_email", columnNames = ["tenant_id", "email"])],
    indexes = [
        Index(name = "ix_users_tenant_status", columnList = "tenant_id,status"),
        Index(name = "ix_users_tenant_role", columnList = "tenant_id,role"),
    ],
)
class UserEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(nullable = false, length = 320)
    var email: String = "",
    @Column(nullable = false, length = 512)
    var passwordHash: String = "",
    @Column(length = 255)
    var name: String? = null,
    @Column(length = 255)
    var fullNameCyrillic: String? = null,
    @Column(length = 255)
    var fullNameLatin: String? = null,
    @Column(length = 32)
    var phone: String? = null,
    var emailVerifiedAt: Instant? = null,
    var termsAcceptedAt: Instant? = null,
    @Column(nullable = false, length = 24)
    var status: String = "active",
    @Column(nullable = false, length = 24)
    var role: String = "user",
    @Column(nullable = false)
    var mfaEnabled: Boolean = false,
    @Column(nullable = false)
    var failedLoginCount: Int = 0,
    var lockedUntil: Instant? = null,
    var lastLoginAt: Instant? = null,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Version
    var version: Long = 0,
) : IdentifiedEntity("usr") {
    val emailVerified: Boolean get() = emailVerifiedAt != null
}

@Entity
@Table(
    name = "user_sessions",
    indexes = [
        Index(name = "ix_sessions_user_active", columnList = "user_id,revoked_at,expires_at"),
        Index(name = "ix_sessions_tenant_active", columnList = "tenant_id,revoked_at,expires_at"),
    ],
)
class UserSessionEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false, length = 64)
    var secretHash: String = "",
    @Column(length = 45)
    var ipAddress: String? = null,
    @Column(length = 512)
    var userAgent: String? = null,
    @Column(length = 64)
    var deviceId: String? = null,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var lastSeenAt: Instant = Instant.now(),
    @Column(nullable = false)
    var expiresAt: Instant = Instant.now(),
    var revokedAt: Instant? = null,
    var mfaCompletedAt: Instant? = null,
    @Column(nullable = false, length = 32)
    var authenticationMethod: String = "password",
    var stepUpAt: Instant? = null,
    @Column(nullable = false)
    var riskScore: Int = 0,
    @Version
    var version: Long = 0,
) : IdentifiedEntity("ses")

@Entity
@Table(
    name = "account_tokens",
    indexes = [Index(name = "ix_account_tokens_user_type", columnList = "user_id,type,expires_at")],
)
class AccountTokenEntity(
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false, length = 24)
    var type: String = "",
    @Column(nullable = false, length = 64)
    var secretHash: String = "",
    @Column(nullable = false)
    var expiresAt: Instant = Instant.now(),
    var usedAt: Instant? = null,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("tok")

@Entity
@Table(
    name = "mfa_methods",
    uniqueConstraints = [UniqueConstraint(name = "uq_mfa_user_type", columnNames = ["user_id", "method_type"])],
)
class MfaMethodEntity(
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(name = "method_type", nullable = false, length = 24)
    var methodType: String = "totp",
    @Column(nullable = false, columnDefinition = "text")
    var secretEncrypted: String = "",
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(nullable = false)
    var verifiedAt: Instant = Instant.now(),
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("mfa")

@Entity
@Table(name = "backup_codes", indexes = [Index(name = "ix_backup_codes_user", columnList = "user_id")])
class BackupCodeEntity(
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false, length = 64)
    var codeHash: String = "",
    var usedAt: Instant? = null,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("bkc")

@Entity
@Table(
    name = "login_history",
    indexes = [
        Index(name = "ix_login_history_user_created", columnList = "user_id,created_at"),
        Index(name = "ix_login_history_tenant_created", columnList = "tenant_id,created_at"),
    ],
)
class LoginHistoryEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "user_id", length = 40)
    var userId: String? = null,
    @Column(nullable = false, length = 320)
    var email: String = "",
    @Column(nullable = false)
    var success: Boolean = false,
    @Column(length = 64)
    var failureReason: String? = null,
    @Column(length = 45)
    var ipAddress: String? = null,
    @Column(length = 512)
    var userAgent: String? = null,
    @Column(nullable = false)
    var riskScore: Int = 0,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("log")

@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "ix_audit_tenant_created", columnList = "tenant_id,created_at"),
        Index(name = "ix_audit_event_created", columnList = "event_type,created_at"),
    ],
)
class AuditLogEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "event_type", nullable = false, length = 96)
    var eventType: String = "",
    @Column(name = "actor_id", length = 40)
    var actorId: String? = null,
    @Column(name = "subject_id", length = 96)
    var subjectId: String? = null,
    @Column(name = "client_id", length = 100)
    var clientId: String? = null,
    @Column(name = "request_id", nullable = false, length = 64)
    var requestId: String = "",
    @Column(length = 45)
    var ipAddress: String? = null,
    @Column(length = 512)
    var userAgent: String? = null,
    @Column(nullable = false, columnDefinition = "text")
    var detailsJson: String = "{}",
    @Column(length = 64)
    var previousHash: String? = null,
    @Column(nullable = false, length = 64)
    var eventHash: String = "",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("aud")

@Entity
@Table(
    name = "user_consents",
    uniqueConstraints = [UniqueConstraint(name = "uq_consent_user_client", columnNames = ["user_id", "client_id"])],
)
class UserConsentEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(name = "client_id", nullable = false, length = 100)
    var clientId: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var scopes: String = "",
    @Column(nullable = false, length = 24)
    var status: String = "granted",
    @Column(nullable = false)
    var grantedAt: Instant = Instant.now(),
    var revokedAt: Instant? = null,
) : IdentifiedEntity("cns")

@Entity
@Table(
    name = "security_policies",
    uniqueConstraints = [UniqueConstraint(name = "uq_policy_tenant_key", columnNames = ["tenant_id", "policy_key"])],
)
class SecurityPolicyEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "policy_key", nullable = false, length = 96)
    var key: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var valueJson: String = "{}",
    @Column(length = 40)
    var updatedBy: String? = null,
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) : IdentifiedEntity("pol")

@Entity
@Table(
    name = "trusted_devices",
    uniqueConstraints = [UniqueConstraint(name = "uq_device_user_fingerprint", columnNames = ["user_id", "fingerprint"])],
)
class TrustedDeviceEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false, length = 64)
    var fingerprint: String = "",
    @Column(length = 512)
    var lastUserAgent: String? = null,
    @Column(length = 45)
    var lastIpAddress: String? = null,
    @Column(nullable = false)
    var firstSeenAt: Instant = Instant.now(),
    @Column(nullable = false)
    var lastSeenAt: Instant = Instant.now(),
    @Column(nullable = false)
    var trusted: Boolean = false,
) : IdentifiedEntity("dev")

@Entity
@Table(name = "risk_assessments", indexes = [Index(name = "ix_risk_user_created", columnList = "user_id,created_at")])
class RiskAssessmentEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(nullable = false)
    var score: Int = 0,
    @Column(nullable = false, length = 24)
    var decision: String = "allow",
    @Column(nullable = false, columnDefinition = "text")
    var reasonsJson: String = "[]",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("rsk")

@Entity
@Table(name = "domain_outbox", indexes = [Index(name = "ix_outbox_unpublished", columnList = "published_at,created_at")])
class DomainOutboxEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(nullable = false, length = 96)
    var eventType: String = "",
    @Column(nullable = false, length = 96)
    var aggregateId: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var payloadJson: String = "{}",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    var publishedAt: Instant? = null,
    @Column(nullable = false)
    var attempts: Int = 0,
    @Column(columnDefinition = "text")
    var lastError: String? = null,
) : IdentifiedEntity("evt")

@Entity
@Table(
    name = "used_refresh_tokens",
    indexes = [
        Index(name = "ix_used_refresh_hash", columnList = "token_hash", unique = true),
        Index(name = "ix_used_refresh_expiry", columnList = "expires_at"),
    ],
)
class UsedRefreshTokenEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(name = "authorization_id", nullable = false, length = 100)
    var authorizationId: String = "",
    @Column(name = "user_id", nullable = false, length = 40)
    var userId: String = "",
    @Column(name = "client_id", nullable = false, length = 100)
    var clientId: String = "",
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    var tokenHash: String = "",
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now(),
    @Column(name = "rotated_at", nullable = false)
    var rotatedAt: Instant = Instant.now(),
    var reusedAt: Instant? = null,
) : IdentifiedEntity("urt")

@Entity
@Table(name = "federation_providers", uniqueConstraints = [UniqueConstraint(name = "uq_federation_tenant_alias", columnNames = ["tenant_id", "alias"])])
class FederationProviderEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(nullable = false, length = 64)
    var alias: String = "",
    @Column(nullable = false, length = 24)
    var protocol: String = "oidc",
    @Column(nullable = false, columnDefinition = "text")
    var configJson: String = "{}",
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) : IdentifiedEntity("idp")

@Entity
@Table(name = "key_metadata", indexes = [Index(name = "ix_keys_level_state", columnList = "confidentiality_level,state")])
class KeyMetadataEntity(
    @Column(name = "tenant_id", nullable = false, length = 40)
    var tenantId: String = "",
    @Column(nullable = false, length = 96)
    var purpose: String = "",
    @Column(nullable = false, length = 32)
    var confidentialityLevel: String = "confidential",
    @Column(nullable = false, length = 32)
    var backend: String = "vault",
    @Column(nullable = false, length = 255)
    var keyReference: String = "",
    @Column(nullable = false)
    var versionNumber: Int = 1,
    @Column(nullable = false, length = 24)
    var state: String = "pending",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    var activatedAt: Instant? = null,
    var rotatedAt: Instant? = null,
    var destroyedAt: Instant? = null,
) : IdentifiedEntity("key")
