package com.example.mypasture.auth.domain

import java.text.Normalizer
import java.util.Locale

/** 한글과 영문만 허용하며 한글 8자, 영문 10자에 해당하는 길이를 검사합니다. */
object NicknamePolicy {
    private val allowed = Regex("^[가-힣A-Za-z]+$")

    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)

    fun isValid(raw: String): Boolean {
        val value = normalize(raw)
        val weight = value.fold(0) { total, character -> total + if (character in '가'..'힣') 5 else 4 }
        return value.isNotEmpty() && allowed.matches(value) && weight <= 40
    }

    fun key(raw: String): String = normalize(raw).lowercase(Locale.ROOT)
}
