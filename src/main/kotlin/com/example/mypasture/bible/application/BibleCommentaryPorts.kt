package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot

/** OpenAI 같은 외부 생성기를 application 계층과 분리하는 장별 해설 생성 port입니다. */
fun interface BibleCommentaryGeneratorPort {
    suspend fun generate(
        chapter: BibleChapter,
        evidence: List<BibleCommentaryEvidence>,
        config: AiRuntimeConfigSnapshot,
        previousCommentary: GeneratedBibleCommentary?,
        revisionIssues: List<String>,
    ): GeneratedBibleCommentary
}

/** 생성된 장별 해설을 본문과 승인 근거에 대조하는 외부 평가 모델 port입니다. */
fun interface BibleCommentaryEvaluatorPort {
    suspend fun evaluate(
        chapter: BibleChapter,
        evidence: List<BibleCommentaryEvidence>,
        commentary: GeneratedBibleCommentary,
        config: AiRuntimeConfigSnapshot,
    ): BibleCommentaryEvaluation
}
