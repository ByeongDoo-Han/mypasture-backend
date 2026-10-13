package com.example.mypasture.bible.domain

/** 성경 번역본과 66권 메타데이터를 제공하는 고정 카탈로그입니다. */
object BibleCatalog {
    const val KOR1910_VERSION = "KOR1910"
    const val KRV_VERSION = "KRV"

    val versions: List<BibleVersion> =
        listOf(
            BibleVersion(
                code = KOR1910_VERSION,
                name = "Korean Bible 1910",
                language = "ko",
                copyright = "Public Domain",
            ),
            BibleVersion(
                code = KRV_VERSION,
                name = "개역한글판",
                language = "ko",
                copyright = "성경전서 개역한글판, 대한성서공회 1961 (저작재산권 보호기간 만료)",
            ),
        )

    val books: List<BibleBook> =
        listOf(
            book("GEN", "창세기", Testament.OLD, 1, 50),
            book("EXO", "출애굽기", Testament.OLD, 2, 40),
            book("LEV", "레위기", Testament.OLD, 3, 27),
            book("NUM", "민수기", Testament.OLD, 4, 36),
            book("DEU", "신명기", Testament.OLD, 5, 34),
            book("JOS", "여호수아", Testament.OLD, 6, 24),
            book("JDG", "사사기", Testament.OLD, 7, 21),
            book("RUT", "룻기", Testament.OLD, 8, 4),
            book("1SA", "사무엘상", Testament.OLD, 9, 31),
            book("2SA", "사무엘하", Testament.OLD, 10, 24),
            book("1KI", "열왕기상", Testament.OLD, 11, 22),
            book("2KI", "열왕기하", Testament.OLD, 12, 25),
            book("1CH", "역대상", Testament.OLD, 13, 29),
            book("2CH", "역대하", Testament.OLD, 14, 36),
            book("EZR", "에스라", Testament.OLD, 15, 10),
            book("NEH", "느헤미야", Testament.OLD, 16, 13),
            book("EST", "에스더", Testament.OLD, 17, 10),
            book("JOB", "욥기", Testament.OLD, 18, 42),
            book("PSA", "시편", Testament.OLD, 19, 150),
            book("PRO", "잠언", Testament.OLD, 20, 31),
            book("ECC", "전도서", Testament.OLD, 21, 12),
            book("SNG", "아가", Testament.OLD, 22, 8),
            book("ISA", "이사야", Testament.OLD, 23, 66),
            book("JER", "예레미야", Testament.OLD, 24, 52),
            book("LAM", "예레미야애가", Testament.OLD, 25, 5),
            book("EZK", "에스겔", Testament.OLD, 26, 48),
            book("DAN", "다니엘", Testament.OLD, 27, 12),
            book("HOS", "호세아", Testament.OLD, 28, 14),
            book("JOL", "요엘", Testament.OLD, 29, 3),
            book("AMO", "아모스", Testament.OLD, 30, 9),
            book("OBA", "오바댜", Testament.OLD, 31, 1),
            book("JON", "요나", Testament.OLD, 32, 4),
            book("MIC", "미가", Testament.OLD, 33, 7),
            book("NAM", "나훔", Testament.OLD, 34, 3),
            book("HAB", "하박국", Testament.OLD, 35, 3),
            book("ZEP", "스바냐", Testament.OLD, 36, 3),
            book("HAG", "학개", Testament.OLD, 37, 2),
            book("ZEC", "스가랴", Testament.OLD, 38, 14),
            book("MAL", "말라기", Testament.OLD, 39, 4),
            book("MAT", "마태복음", Testament.NEW, 40, 28),
            book("MRK", "마가복음", Testament.NEW, 41, 16),
            book("LUK", "누가복음", Testament.NEW, 42, 24),
            book("JHN", "요한복음", Testament.NEW, 43, 21),
            book("ACT", "사도행전", Testament.NEW, 44, 28),
            book("ROM", "로마서", Testament.NEW, 45, 16),
            book("1CO", "고린도전서", Testament.NEW, 46, 16),
            book("2CO", "고린도후서", Testament.NEW, 47, 13),
            book("GAL", "갈라디아서", Testament.NEW, 48, 6),
            book("EPH", "에베소서", Testament.NEW, 49, 6),
            book("PHP", "빌립보서", Testament.NEW, 50, 4),
            book("COL", "골로새서", Testament.NEW, 51, 4),
            book("1TH", "데살로니가전서", Testament.NEW, 52, 5),
            book("2TH", "데살로니가후서", Testament.NEW, 53, 3),
            book("1TI", "디모데전서", Testament.NEW, 54, 6),
            book("2TI", "디모데후서", Testament.NEW, 55, 4),
            book("TIT", "디도서", Testament.NEW, 56, 3),
            book("PHM", "빌레몬서", Testament.NEW, 57, 1),
            book("HEB", "히브리서", Testament.NEW, 58, 13),
            book("JAS", "야고보서", Testament.NEW, 59, 5),
            book("1PE", "베드로전서", Testament.NEW, 60, 5),
            book("2PE", "베드로후서", Testament.NEW, 61, 3),
            book("1JN", "요한일서", Testament.NEW, 62, 5),
            book("2JN", "요한이서", Testament.NEW, 63, 1),
            book("3JN", "요한삼서", Testament.NEW, 64, 1),
            book("JUD", "유다서", Testament.NEW, 65, 1),
            book("REV", "요한계시록", Testament.NEW, 66, 22),
        )

    private val bookByCode = books.associateBy { it.code }
    private val bookByNormalizedName = books.associateBy { normalize(it.name) }

    fun getVersion(version: String): BibleVersion? =
        versions.firstOrNull { it.code.equals(version, ignoreCase = true) }

    fun findBook(bookCodeOrName: String): BibleBook? =
        bookByCode[bookCodeOrName.uppercase()] ?: bookByNormalizedName[normalize(bookCodeOrName)]

    fun previousOf(book: BibleBook, chapter: Int): BibleChapterPointer? =
        when {
            chapter > 1 -> BibleChapterPointer(book.code, book.name, chapter - 1)
            book.displayOrder == 1 -> null
            else -> books[book.displayOrder - 2].let { BibleChapterPointer(it.code, it.name, it.chapterCount) }
        }

    fun nextOf(book: BibleBook, chapter: Int): BibleChapterPointer? =
        when {
            chapter < book.chapterCount -> BibleChapterPointer(book.code, book.name, chapter + 1)
            book.displayOrder == books.size -> null
            else -> books[book.displayOrder].let { BibleChapterPointer(it.code, it.name, 1) }
        }

    private fun book(
        code: String,
        name: String,
        testament: Testament,
        displayOrder: Int,
        chapterCount: Int,
    ): BibleBook = BibleBook(code, name, testament, displayOrder, chapterCount)

    private fun normalize(value: String): String = value.replace(" ", "").lowercase()
}
