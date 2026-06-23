package uz.ods.sso.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TenantRepository : JpaRepository<TenantEntity, UUID> {
    fun findBySlug(slug: String): TenantEntity?
    fun findByPublicId(publicId: String): TenantEntity?
}

interface PartnerOrganizationRepository : JpaRepository<PartnerOrganizationEntity, UUID> {
    fun findByPublicId(publicId: String): PartnerOrganizationEntity?
    fun findByTenantIdAndSlug(tenantId: String, slug: String): PartnerOrganizationEntity?
    fun findBySlug(slug: String): PartnerOrganizationEntity?
    fun findBySlugAndStatus(slug: String, status: String): PartnerOrganizationEntity?
}

interface PartnerMembershipRepository : JpaRepository<PartnerMembershipEntity, UUID> {
    fun findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId: String, status: String): PartnerMembershipEntity?
}

interface PartnerApplicationRepository : JpaRepository<PartnerApplicationEntity, UUID> {
    fun findByOrganizationIdOrderByCreatedAtDesc(organizationId: String): List<PartnerApplicationEntity>
    fun findByPublicIdAndOrganizationId(publicId: String, organizationId: String): PartnerApplicationEntity?
}

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByPublicId(publicId: String): UserEntity?
    fun findByTenantIdAndEmailIgnoreCase(tenantId: String, email: String): UserEntity?

    @Query(
        """
        select u from UserEntity u
        where u.tenantId = :tenantId
          and (:query is null or lower(u.email) like lower(concat('%', :query, '%'))
               or lower(coalesce(u.name, '')) like lower(concat('%', :query, '%')))
        order by u.createdAt desc
        """,
    )
    fun search(tenantId: String, query: String?, pageable: Pageable): List<UserEntity>

    fun countByTenantId(tenantId: String): Long
    fun countByTenantIdAndStatus(tenantId: String, status: String): Long
}

interface UserSessionRepository : JpaRepository<UserSessionEntity, UUID> {
    fun findByPublicId(publicId: String): UserSessionEntity?
    fun findByPublicIdAndUserId(publicId: String, userId: String): UserSessionEntity?

    @Query(
        "select s from UserSessionEntity s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now order by s.lastSeenAt desc",
    )
    fun findActiveByUserId(userId: String, now: Instant): List<UserSessionEntity>

    @Query(
        "select s from UserSessionEntity s where s.tenantId = :tenantId and (:userId is null or s.userId = :userId) order by s.lastSeenAt desc",
    )
    fun findForAdmin(tenantId: String, userId: String?, pageable: Pageable): List<UserSessionEntity>

    fun countByTenantIdAndRevokedAtIsNullAndExpiresAtAfter(tenantId: String, now: Instant): Long

    @Modifying
    @Query(
        "update UserSessionEntity s set s.lastSeenAt = :now where s.publicId = :sessionId and s.lastSeenAt < :cutoff",
    )
    fun touch(sessionId: String, now: Instant, cutoff: Instant): Int

    @Modifying
    @Query("update UserSessionEntity s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null and (:exceptId is null or s.publicId <> :exceptId)")
    fun revokeAll(userId: String, now: Instant, exceptId: String? = null): Int
}

interface AccountTokenRepository : JpaRepository<AccountTokenEntity, UUID> {
    fun findByPublicIdAndType(publicId: String, type: String): AccountTokenEntity?

    @Modifying
    @Query("update AccountTokenEntity t set t.usedAt = :now where t.userId = :userId and t.type = :type and t.usedAt is null")
    fun invalidate(userId: String, type: String, now: Instant): Int
}

interface MfaMethodRepository : JpaRepository<MfaMethodEntity, UUID> {
    fun findByUserIdAndMethodTypeAndEnabledTrue(userId: String, methodType: String): MfaMethodEntity?
    fun findByUserIdAndMethodType(userId: String, methodType: String): MfaMethodEntity?
    fun deleteByUserId(userId: String)
}

interface BackupCodeRepository : JpaRepository<BackupCodeEntity, UUID> {
    fun findByUserId(userId: String): List<BackupCodeEntity>
    fun deleteByUserId(userId: String)
}

interface LoginHistoryRepository : JpaRepository<LoginHistoryEntity, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<LoginHistoryEntity>
    fun countByTenantIdAndSuccessFalseAndCreatedAtAfter(tenantId: String, since: Instant): Long
}

interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID> {
    fun findFirstByTenantIdOrderByCreatedAtDesc(tenantId: String): AuditLogEntity?

    @Query(
        """
        select a from AuditLogEntity a where a.tenantId = :tenantId
          and (:eventType is null or a.eventType = :eventType)
          and (:actorId is null or a.actorId = :actorId)
        order by a.createdAt desc
        """,
    )
    fun search(tenantId: String, eventType: String?, actorId: String?, pageable: Pageable): List<AuditLogEntity>

    fun countByTenantIdAndCreatedAtAfter(tenantId: String, since: Instant): Long
}

interface UserConsentRepository : JpaRepository<UserConsentEntity, UUID> {
    fun findByUserIdAndStatus(userId: String, status: String): List<UserConsentEntity>
    fun findByPublicIdAndUserId(publicId: String, userId: String): UserConsentEntity?
    fun findByUserIdAndClientId(userId: String, clientId: String): UserConsentEntity?
}

interface SecurityPolicyRepository : JpaRepository<SecurityPolicyEntity, UUID> {
    fun findByTenantIdOrderByKey(tenantId: String): List<SecurityPolicyEntity>
    fun findByTenantIdAndKey(tenantId: String, key: String): SecurityPolicyEntity?
}

interface TrustedDeviceRepository : JpaRepository<TrustedDeviceEntity, UUID> {
    fun findByUserIdAndFingerprint(userId: String, fingerprint: String): TrustedDeviceEntity?
}

interface RiskAssessmentRepository : JpaRepository<RiskAssessmentEntity, UUID>

interface DomainOutboxRepository : JpaRepository<DomainOutboxEntity, UUID> {
    fun findByPublishedAtIsNullOrderByCreatedAt(pageable: Pageable): List<DomainOutboxEntity>
}

interface UsedRefreshTokenRepository : JpaRepository<UsedRefreshTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): UsedRefreshTokenEntity?

    @Modifying
    @Query("delete from UsedRefreshTokenEntity t where t.expiresAt < :now")
    fun deleteExpired(now: Instant): Int
}

interface FederationProviderRepository : JpaRepository<FederationProviderEntity, UUID>
interface KeyMetadataRepository : JpaRepository<KeyMetadataEntity, UUID>
