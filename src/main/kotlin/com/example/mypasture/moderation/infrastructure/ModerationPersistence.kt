package com.example.mypasture.moderation.infrastructure

import com.example.mypasture.config.AiProperties
import com.example.mypasture.moderation.domain.ModerationAction
import com.example.mypasture.moderation.domain.ModerationReportEntity
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import com.example.mypasture.moderation.domain.UserBlockEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 신고의 멱등 갱신, 관리자 대기열과 숨김 콘텐츠를 조회합니다. */
@Repository
interface ModerationReportRepository : JpaRepository<ModerationReportEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByReporterUserIdAndTargetTypeAndTargetId(
        reporterUserId: UUID,
        targetType: ModerationTargetType,
        targetId: UUID,
    ): ModerationReportEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ModerationReportEntity report where report.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ModerationReportEntity?

    @Query(
        """
        select report from ModerationReportEntity report
         where (:status is null or report.status = :status)
           and (:targetType is null or report.targetType = :targetType)
        """,
    )
    fun findAdminPage(
        @Param("status") status: ModerationReportStatus?,
        @Param("targetType") targetType: ModerationTargetType?,
        pageable: Pageable,
    ): Page<ModerationReportEntity>

    @Query(
        """
        select distinct report.targetId from ModerationReportEntity report
         where report.targetType = :targetType
           and report.targetId in :targetIds
           and report.action = :action
        """,
    )
    fun findTargetIdsByAction(
        @Param("targetType") targetType: ModerationTargetType,
        @Param("targetIds") targetIds: Collection<UUID>,
        @Param("action") action: ModerationAction,
    ): Set<UUID>

    fun existsByTargetTypeAndTargetIdAndAction(
        targetType: ModerationTargetType,
        targetId: UUID,
        action: ModerationAction,
    ): Boolean
}

/** 사용자별 차단 목록과 실시간 전송 제외 대상을 조회합니다. */
@Repository
interface UserBlockRepository : JpaRepository<UserBlockEntity, UUID> {
    fun findByBlockerUserIdAndBlockedUserId(blockerUserId: UUID, blockedUserId: UUID): UserBlockEntity?
    fun findAllByBlockerUserIdOrderByCreatedAtDesc(blockerUserId: UUID): List<UserBlockEntity>

    @Query("select block.blockedUserId from UserBlockEntity block where block.blockerUserId = :blockerUserId")
    fun findBlockedUserIds(@Param("blockerUserId") blockerUserId: UUID): Set<UUID>

    @Query("select block.blockerUserId from UserBlockEntity block where block.blockedUserId = :blockedUserId")
    fun findBlockerUserIds(@Param("blockedUserId") blockedUserId: UUID): Set<UUID>
}

/** 신고 상세와 대상 스냅샷을 용도별 AES-GCM 키로 암호화하고 운영 조회 때만 복호화합니다. */
@Component
class ModerationCipher(properties: AiProperties) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest((properties.privacyHashSecret + ":moderation:v1").toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(value: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(value: String): String {
        val decoded = Base64.getDecoder().decode(value)
        require(decoded.size > 12) { "Encrypted moderation text is invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoded.copyOfRange(0, 12)))
        return cipher.doFinal(decoded.copyOfRange(12, decoded.size)).toString(Charsets.UTF_8)
    }
}
