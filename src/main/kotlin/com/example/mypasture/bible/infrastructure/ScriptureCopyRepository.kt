package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.ScriptureCopyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 본인 필사 기록을 수정 시각 순서로 조회하고 영속화합니다. */
interface ScriptureCopyRepository : JpaRepository<ScriptureCopyEntity, UUID>, ScriptureCopyCreationRepository {
    fun findByIdAndUserId(id: UUID, userId: UUID): ScriptureCopyEntity?
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<ScriptureCopyEntity>
}

/** 클라이언트 UUID 신규 기록을 merge 없이 삽입해 다른 사용자 기록을 덮어쓰지 않습니다. */
interface ScriptureCopyCreationRepository {
    fun insert(record: ScriptureCopyEntity): ScriptureCopyEntity
}

/** ID 충돌은 DB 제약으로 실패시키는 신규 필사 삽입 어댑터입니다. */
class ScriptureCopyCreationRepositoryImpl(
    private val entityManager: jakarta.persistence.EntityManager,
) : ScriptureCopyCreationRepository {
    override fun insert(record: ScriptureCopyEntity): ScriptureCopyEntity {
        entityManager.persist(record)
        entityManager.flush()
        return record
    }
}
