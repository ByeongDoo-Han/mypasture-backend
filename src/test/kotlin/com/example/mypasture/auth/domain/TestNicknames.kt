package com.example.mypasture.auth.domain

import java.util.UUID

/** 통합 테스트마다 겹치지 않는 유효한 한글 닉네임을 만듭니다. */
fun uniqueTestNickname(label: String = "목원"): String {
    val prefix = label.filter { it in '가'..'힣' }.take(2).padEnd(2, '목')
    val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        .map { ('가'.code + it.digitToInt(16)).toChar() }.joinToString("")
    return prefix + suffix
}
