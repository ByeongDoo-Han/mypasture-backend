package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleSearchPosition
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/** 공개 본문 탐색 위치를 검색어·번역본에 묶고 잘못된 cursor를 안전하게 거절합니다. */
object BibleSearchCursorCodec {
    private const val INVALID_CURSOR = "Search cursor is invalid. Start a new search."
    private val alphabet = Regex("[A-Za-z0-9_-]+")

    fun normalizeKeyword(keyword: String): String {
        val normalized = Normalizer.normalize(keyword, Normalizer.Form.NFC).trim()
        require(normalized.codePointCount(0, normalized.length) in 1..50 && '\u0000' !in normalized) { "Keyword must contain between 1 and 50 characters." }
        return normalized
    }

    fun encode(version: String, keyword: String, position: BibleSearchPosition): String {
        val payload = listOf("1", version.uppercase(Locale.ROOT), fingerprint(keyword), position.bookOrder, position.chapter, position.verse, position.id).joinToString("|")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(UTF_8))
    }

    fun decode(cursor: String, version: String, keyword: String): BibleSearchPosition {
        require(cursor.length in 1..512 && alphabet.matches(cursor)) { INVALID_CURSOR }
        val bytes = try { Base64.getUrlDecoder().decode(cursor) } catch (_: IllegalArgumentException) { throw IllegalArgumentException(INVALID_CURSOR) }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == cursor) { INVALID_CURSOR }
        val parts = String(bytes, UTF_8).split('|')
        require(parts.size == 7 && parts[0] == "1" && parts[1] == version.uppercase(Locale.ROOT) && parts[2] == fingerprint(keyword)) { INVALID_CURSOR }
        val bookOrder = parts[3].toIntOrNull()
        val chapter = parts[4].toIntOrNull()
        val verse = parts[5].toIntOrNull()
        val id = parts[6].toLongOrNull()
        val book = BibleCatalog.books.find { it.displayOrder == bookOrder }
        require(book != null && chapter != null && chapter in 1..book.chapterCount && verse != null && verse in 1..200 && id != null && id > 0) { INVALID_CURSOR }
        return BibleSearchPosition(bookOrder!!, chapter, verse, id)
    }

    private fun fingerprint(keyword: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalizeKeyword(keyword).lowercase(Locale.ROOT).toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
}
