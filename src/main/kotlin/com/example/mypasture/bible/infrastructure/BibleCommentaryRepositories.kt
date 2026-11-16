package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleChapterCommentaryEntity
import com.example.mypasture.bible.domain.BibleCommentaryGenerationJobEntity
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceEntity
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceStatus
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryReviewEventEntity
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** 장별 해설 버전과 게시 상태를 영속화하는 Spring Data repository입니다. */
interface BibleChapterCommentaryRepository : JpaRepository<BibleChapterCommentaryEntity, UUID> {
    fun findAllByVersionAndBookCode(version: String, bookCode: String): List<BibleChapterCommentaryEntity>

    fun findFirstByVersionAndBookCodeAndChapterAndStatusOrderByRevisionDesc(
        version: String,
        bookCode: String,
        chapter: Int,
        status: BibleCommentaryStatus,
    ): BibleChapterCommentaryEntity?

    fun findAllByOrderByCreatedAtDesc(): List<BibleChapterCommentaryEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByVersionAndBookCodeAndChapterOrderByRevisionDesc(
        version: String,
        bookCode: String,
        chapter: Int,
    ): BibleChapterCommentaryEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select commentary from BibleChapterCommentaryEntity commentary where commentary.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): BibleChapterCommentaryEntity?

    fun existsByVersionAndBookCodeAndChapterAndStatus(
        version: String,
        bookCode: String,
        chapter: Int,
        status: BibleCommentaryStatus,
    ): Boolean
}

/** 장별 해설 생성에 사용할 관리자 승인 근거 메모를 조회합니다. */
interface BibleCommentaryEvidenceRepository : JpaRepository<BibleCommentaryEvidenceEntity, UUID> {
    @Query(
        """
        select evidence from BibleCommentaryEvidenceEntity evidence
        where evidence.version = :version
          and evidence.bookCode = :bookCode
          and (evidence.chapter is null or evidence.chapter = :chapter)
          and evidence.status = :status
        order by evidence.chapter nulls first, evidence.createdAt
        """,
    )
    fun findApplicable(
        version: String,
        bookCode: String,
        chapter: Int,
        status: BibleCommentaryEvidenceStatus,
    ): List<BibleCommentaryEvidenceEntity>

    fun findAllByVersionAndBookCodeAndChapterIsNullOrVersionAndBookCodeAndChapterOrderByCreatedAtDesc(
        bookVersion: String,
        bookCode: String,
        chapterVersion: String,
        chapterBookCode: String,
        chapter: Int,
    ): List<BibleCommentaryEvidenceEntity>
}

/** 사람 검수 행위를 시간 순서로 보존하고 조회합니다. */
interface BibleCommentaryReviewEventRepository : JpaRepository<BibleCommentaryReviewEventEntity, Long> {
    fun findAllByCommentaryIdOrderByCreatedAtAsc(commentaryId: UUID): List<BibleCommentaryReviewEventEntity>
}

/** 장별 해설 생성 큐를 영속화하고 한 번에 한 워커만 작업을 점유하도록 잠급니다. */
interface BibleCommentaryGenerationJobRepository : JpaRepository<BibleCommentaryGenerationJobEntity, UUID> {
    fun findAllByVersionAndBookCode(version: String, bookCode: String): List<BibleCommentaryGenerationJobEntity>

    fun existsByVersionAndBookCodeAndChapterAndStatusIn(
        version: String,
        bookCode: String,
        chapter: Int,
        statuses: Collection<BibleCommentaryJobStatus>,
    ): Boolean

    fun findAllByOrderByCreatedAtDesc(): List<BibleCommentaryGenerationJobEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from BibleCommentaryGenerationJobEntity job where job.status = :status order by job.createdAt")
    fun lockNext(
        status: BibleCommentaryJobStatus,
        pageable: Pageable,
    ): List<BibleCommentaryGenerationJobEntity>
}
