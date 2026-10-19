package com.example.mypasture.ai.presentation

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

/** Swagger 문서와 구현을 분리한 성경 embedding index 관리자 API contract입니다. */
@Tag(name = "관리자 · 성경 임베딩", description = "성경 검색 임베딩 인덱스의 진행 상태를 관리합니다.")
interface AiEmbeddingAdminApi {
    @Operation(summary = "임베딩 인덱스 상태 조회", description = "관리자가 성경 절 임베딩의 인덱싱 진행 상태를 조회합니다.")
    @GetMapping
    fun status(): BibleEmbeddingIndexResponse

    @Operation(summary = "다음 임베딩 묶음 처리", description = "관리자가 아직 인덱싱되지 않은 성경 절의 다음 묶음을 처리합니다.")
    @PostMapping("/index-next-batch")
    fun indexNextBatch(): BibleEmbeddingIndexResponse
}
