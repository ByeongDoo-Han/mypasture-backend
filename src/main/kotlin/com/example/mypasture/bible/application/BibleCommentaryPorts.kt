package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleChapter

/** OpenAI 같은 외부 생성기를 application 계층과 분리하는 장별 해설 생성 port입니다. */
fun interface BibleCommentaryGeneratorPort {
    fun generate(chapter: BibleChapter): GeneratedBibleCommentary
}
