package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleChapterCommentaryEntity
import com.example.mypasture.bible.domain.BibleCommentaryGenerationJobEntity
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** 장별 해설 버전과 게시 상태를 영속화하는 Spring Data repository입니다. */
interface BibleChapterCommentaryRepository : JpaRepository<BibleChapterCommentaryEntity, UUID> {
    fun findFirstByVersionAndBookCodeAndChapterAndStatusOrderByRevisionDesc(
        version: String,
        bookCode: String,
        chapter: Int,
        status: BibleCommentaryStatus,
    ): BibleChapterCommentaryEntity?

    fun findAllByOrderByCreatedAtDesc(): List<BibleChapterCommentaryEntity>

    fun findFirstByVersionAndBookCodeAndChapterOrderByRevisionDesc(
        version: String,
        bookCode: String,
        chapter: Int,
    ): BibleChapterCommentaryEntity?
}

/** 장별 해설 생성 큐를 영속화하고 한 번에 한 워커만 작업을 점유하도록 잠급니다. */
interface BibleCommentaryGenerationJobRepository : JpaRepository<BibleCommentaryGenerationJobEntity, UUID> {
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
