package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.domain.AiRuntimeConfigPointerEntity
import com.example.mypasture.ai.domain.AiRuntimeConfigVersionEntity
import com.example.mypasture.ai.domain.AiUseCase
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** 활성 AI 설정 포인터를 조회하고 관리자 변경 시 비관적 잠금을 적용합니다. */
interface AiRuntimeConfigPointerRepository : JpaRepository<AiRuntimeConfigPointerEntity, AiUseCase> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select config from AiRuntimeConfigPointerEntity config where config.useCase = :useCase")
    fun findByUseCaseForUpdate(@Param("useCase") useCase: AiUseCase): AiRuntimeConfigPointerEntity?
}

/** 불변 AI 설정 버전과 관리자 이력을 저장합니다. */
interface AiRuntimeConfigVersionRepository : JpaRepository<AiRuntimeConfigVersionEntity, UUID> {
    fun findTop20ByUseCaseOrderByVersionNumberDesc(useCase: AiUseCase): List<AiRuntimeConfigVersionEntity>
    fun countByUseCase(useCase: AiUseCase): Long
}
