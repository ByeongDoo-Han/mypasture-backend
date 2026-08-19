package com.example.mypasture.ai.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

/** Swagger 문서와 구현을 분리한 성경 embedding index 관리자 API contract입니다. */
interface AiEmbeddingAdminApi {
    @GetMapping
    fun status(): BibleEmbeddingIndexResponse

    @PostMapping("/index-next-batch")
    fun indexNextBatch(): BibleEmbeddingIndexResponse
}
