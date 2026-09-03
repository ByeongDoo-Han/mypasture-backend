package com.example.mypasture.quiet_time.application

import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimePassageThemeEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import com.example.mypasture.quiet_time.domain.QuietTimeThemeEntity
import com.example.mypasture.quiet_time.infrastructure.QuietTimePassageRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimePassageThemeRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeThemeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 본문별 복수 주제를 관리하고 선택한 초점에 맞는 QT 질문 스냅샷을 만듭니다. */
@Service
class QuietTimeQuestionSetService(
    private val passageRepository: QuietTimePassageRepository,
    private val themeRepository: QuietTimeThemeRepository,
    private val passageThemeRepository: QuietTimePassageThemeRepository,
) {
    @Transactional
    fun themesForPassage(passageId: UUID): List<QuietTimePassageThemeView> {
        val passage = findPassage(passageId)
        return ensurePassageThemes(passage).map { it.toView() }
    }

    @Transactional
    fun preview(
        passageId: UUID,
        focusThemeCode: String,
        style: QuietTimeQuestionStyle,
    ): QuietTimeQuestionSet =
        buildQuestionSet(findPassage(passageId), focusThemeCode, style)

    @Transactional
    fun selectForSession(
        passage: QuietTimePassageEntity,
        focusThemeCode: String?,
        legacyTheme: String?,
        style: QuietTimeQuestionStyle?,
    ): QuietTimeQuestionSet {
        val themes = ensurePassageThemes(passage)
        val requested = focusThemeCode?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val legacyRequested = legacyTheme?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val selected = when {
            requested != null -> themes.firstOrNull { it.theme.code == requested }
            legacyRequested != null -> themes.firstOrNull {
                it.theme.name.lowercase().contains(legacyRequested) || legacyRequested.contains(it.theme.name.lowercase())
            }
            else -> null
        } ?: themes.firstOrNull { it.primaryTheme } ?: themes.first()

        if (requested != null && selected.theme.code != requested) {
            throw IllegalArgumentException("선택한 주제는 이 성경 본문에 포함되지 않습니다.")
        }
        return createQuestions(passage, selected, style ?: QuietTimeQuestionStyle.BALANCED)
    }

    private fun buildQuestionSet(
        passage: QuietTimePassageEntity,
        focusThemeCode: String,
        style: QuietTimeQuestionStyle,
    ): QuietTimeQuestionSet {
        val normalizedCode = focusThemeCode.trim().uppercase()
        require(normalizedCode.matches(THEME_CODE_PATTERN)) { "주제 코드 형식이 올바르지 않습니다." }
        val link = ensurePassageThemes(passage).firstOrNull { it.theme.code == normalizedCode }
            ?: throw IllegalArgumentException("선택한 주제는 이 성경 본문에 포함되지 않습니다.")
        return createQuestions(passage, link, style)
    }

    private fun createQuestions(
        passage: QuietTimePassageEntity,
        link: QuietTimePassageThemeEntity,
        style: QuietTimeQuestionStyle,
    ): QuietTimeQuestionSet {
        val themeName = link.theme.name
        val sourceObservation = passage.observationQuestions.toQuestions().firstOrNull()
            ?: "본문에서 반복되거나 강조되는 표현은 무엇인가요?"
        val evidenceQuestion = "${link.evidenceReference}에서 '$themeName'과 연결되는 표현은 무엇인가요?"
        val observation = when (style) {
            QuietTimeQuestionStyle.NEW_BELIEVER -> listOf("본문에서 가장 마음에 남는 단어나 문장은 무엇인가요?")
            QuietTimeQuestionStyle.REFLECTION -> listOf(evidenceQuestion)
            else -> listOf(sourceObservation, evidenceQuestion).distinct()
        }
        val meditation = when (style) {
            QuietTimeQuestionStyle.REFLECTION -> listOf(link.reflectionPrompt, "하나님이 '$themeName'을 통해 내 마음에 비추시는 부분은 무엇인가요?")
            QuietTimeQuestionStyle.NEW_BELIEVER -> listOf("이 말씀은 하나님과 나에 대해 무엇을 알려 주나요?")
            else -> listOf(link.reflectionPrompt)
        }
        val sharing = when (style) {
            QuietTimeQuestionStyle.SHARING -> listOf(link.sharingPrompt, "서로의 '$themeName'을 위해 이번 주 어떻게 기도하면 좋을까요?")
            QuietTimeQuestionStyle.NEW_BELIEVER -> listOf("이 말씀과 연결해 함께 나누고 싶은 경험이 있나요?")
            else -> listOf(link.sharingPrompt)
        }
        val actionGuide = when (style) {
            QuietTimeQuestionStyle.REFLECTION -> "오늘은 결론을 서두르지 말고 '$themeName'에 관한 마음을 한 문장으로 기록하세요."
            QuietTimeQuestionStyle.SHARING -> "한 사람의 나눔을 판단하지 않고 듣고, '$themeName'을 위한 기도 제목을 남기세요."
            QuietTimeQuestionStyle.NEW_BELIEVER -> "오늘 이해한 말씀을 쉬운 한 문장으로 적어 보세요."
            else -> link.actionPrompt
        }
        return QuietTimeQuestionSet(
            focusTheme = link.toView(),
            style = style,
            version = QUESTION_SET_VERSION,
            observationQuestions = observation,
            meditationQuestions = meditation,
            sharingQuestions = sharing,
            actionGuide = actionGuide,
            prayerGuide = "${themeName}의 의미를 오늘의 삶에서 깨닫고 실천하도록 도와주세요.",
        )
    }

    private fun ensurePassageThemes(passage: QuietTimePassageEntity): List<QuietTimePassageThemeEntity> {
        val existing = passageThemeRepository.findAllByPassageIdOrderByPrimaryThemeDescRelevanceScoreDesc(passage.id)
        if (existing.isNotEmpty()) return existing

        val definitions = passageDefinitions[passage.bookCode to passage.startChapter] ?: listOf(
            PassageThemeDefinition("GENERAL", 70, true, passage.reference(), "이 본문이 오늘 내 삶에 주는 초대는 무엇인가요?", "이 말씀과 연결해 나누고 싶은 삶의 장면은 무엇인가요?", "말씀에서 발견한 한 가지를 오늘 실천하세요."),
        )
        val themes = definitions.map { definition ->
            val theme = themeRepository.findByCodeAndActiveTrue(definition.code)
                ?: themeRepository.save(themeCatalog.getValue(definition.code).toEntity())
            passageThemeRepository.save(
                QuietTimePassageThemeEntity(
                    passage = passage,
                    theme = theme,
                    relevanceScore = definition.score,
                    primaryTheme = definition.primary,
                    evidenceReference = definition.evidenceReference,
                    reflectionPrompt = definition.reflectionPrompt,
                    sharingPrompt = definition.sharingPrompt,
                    actionPrompt = definition.actionPrompt,
                ),
            )
        }
        return themes.sortedWith(compareByDescending<QuietTimePassageThemeEntity> { it.primaryTheme }.thenByDescending { it.relevanceScore })
    }

    private fun findPassage(passageId: UUID): QuietTimePassageEntity =
        passageRepository.findById(passageId)
            .orElseThrow { QuietTimeNotFoundException("Quiet time passage was not found: $passageId") }

    private fun QuietTimePassageThemeEntity.toView() = QuietTimePassageThemeView(
        code = theme.code,
        name = theme.name,
        parentCode = theme.parentCode,
        description = theme.description,
        relevanceScore = relevanceScore,
        primary = primaryTheme,
        evidenceReference = evidenceReference,
    )

    private fun QuietTimePassageEntity.reference() = "$bookName $startChapter:$startVerse-$endChapter:$endVerse"

    private fun String.toQuestions() = split("|").map(String::trim).filter(String::isNotBlank)

    private data class ThemeDefinition(
        val code: String,
        val name: String,
        val parentCode: String?,
        val description: String,
    ) {
        fun toEntity() = QuietTimeThemeEntity(code = code, name = name, parentCode = parentCode, description = description)
    }

    private data class PassageThemeDefinition(
        val code: String,
        val score: Int,
        val primary: Boolean,
        val evidenceReference: String,
        val reflectionPrompt: String,
        val sharingPrompt: String,
        val actionPrompt: String,
    )

    companion object {
        private const val QUESTION_SET_VERSION = 1
        private val THEME_CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,49}$")
        private val themeCatalog = listOf(
            ThemeDefinition("FAITH", "믿음", null, "하나님을 알고 신뢰하며 따르는 삶의 상위 주제입니다."),
            ThemeDefinition("INNER_LIFE", "내면", null, "마음과 영혼의 변화에 관한 상위 주제입니다."),
            ThemeDefinition("SPIRITUAL_PRACTICE", "신앙 훈련", null, "기도와 예배 등 반복되는 신앙 실천의 상위 주제입니다."),
            ThemeDefinition("RELATIONSHIP", "관계", null, "이웃과 공동체 안에서 살아가는 태도의 상위 주제입니다."),
            ThemeDefinition("GENERAL", "말씀 묵상", null, "본문 전체의 중심 메시지를 삶과 연결합니다."),
            ThemeDefinition("TRUST", "신뢰", "FAITH", "상황보다 하나님을 신뢰하는 태도를 살핍니다."),
            ThemeDefinition("PEACE", "평안", "INNER_LIFE", "염려 가운데 하나님이 주시는 평안을 살핍니다."),
            ThemeDefinition("PRAYER", "기도", "SPIRITUAL_PRACTICE", "하나님께 마음을 아뢰고 응답하는 삶을 살핍니다."),
            ThemeDefinition("GUIDANCE", "인도", "FAITH", "하나님의 동행과 인도하심을 살핍니다."),
            ThemeDefinition("RESTORATION", "회복", "INNER_LIFE", "하나님이 영혼과 관계를 새롭게 하심을 살핍니다."),
            ThemeDefinition("REST", "쉼", "INNER_LIFE", "하나님 안에서 멈추고 쉼을 누리는 태도를 살핍니다."),
            ThemeDefinition("COMMUNITY", "공동체", "RELATIONSHIP", "함께 믿음을 살아 내는 공동체의 모습을 살핍니다."),
            ThemeDefinition("SHARING", "나눔", "RELATIONSHIP", "시간과 필요를 서로 나누는 태도를 살핍니다."),
            ThemeDefinition("WORSHIP", "예배", "SPIRITUAL_PRACTICE", "공동체가 하나님께 드리는 예배의 삶을 살핍니다."),
            ThemeDefinition("FORGIVENESS", "용서", "RELATIONSHIP", "그리스도 안에서 용서하고 화해하는 태도를 살핍니다."),
            ThemeDefinition("SPEECH", "말", "RELATIONSHIP", "사람을 세우는 말과 진실한 소통을 살핍니다."),
        ).associateBy(ThemeDefinition::code)

        private val passageDefinitions = mapOf(
            ("PHP" to 4) to listOf(
                PassageThemeDefinition("PEACE", 98, true, "빌립보서 4:7", "염려하는 마음에 하나님의 평강이 필요한 부분은 어디인가요?", "평안을 위해 함께 맡기고 싶은 염려는 무엇인가요?", "염려 한 가지를 감사가 포함된 기도로 바꾸어 기록하세요."),
                PassageThemeDefinition("PRAYER", 92, false, "빌립보서 4:6", "나는 염려를 어떤 기도로 하나님께 아뢰고 있나요?", "서로를 위해 구체적으로 기도할 제목은 무엇인가요?", "오늘 한 가지 염려를 구체적인 간구로 바꾸어 기도하세요."),
                PassageThemeDefinition("TRUST", 84, false, "빌립보서 4:6-7", "결과를 통제하려는 마음 대신 하나님께 맡길 부분은 무엇인가요?", "하나님을 신뢰하기 어려운 상황을 안전하게 나누어 보세요.", "통제하려던 일 한 가지를 내려놓는 행동을 선택하세요."),
            ),
            ("PSA" to 23) to listOf(
                PassageThemeDefinition("GUIDANCE", 98, true, "시편 23:2-3", "선한 목자의 인도가 지금 내 삶에 필요한 영역은 어디인가요?", "최근 경험한 하나님의 인도를 한 가지 나누어 보세요.", "오늘 결정 하나를 기도로 묻고 서두르지 마세요."),
                PassageThemeDefinition("REST", 91, false, "시편 23:2", "하나님 안에서 쉬지 못하게 하는 마음은 무엇인가요?", "서로에게 쉼이 되는 공동체가 되려면 무엇이 필요할까요?", "하루 중 10분을 정해 말씀 앞에서 조용히 쉬세요."),
                PassageThemeDefinition("RESTORATION", 87, false, "시편 23:3", "하나님께서 회복시키기 원하시는 내 영혼의 자리는 어디인가요?", "회복을 위해 공동체에 부탁하고 싶은 도움은 무엇인가요?", "회복이 필요한 영역을 한 문장으로 기록하고 기도하세요."),
            ),
            ("ACT" to 2) to listOf(
                PassageThemeDefinition("COMMUNITY", 98, true, "사도행전 2:42-47", "초대교회의 모습 중 우리 공동체가 회복할 부분은 무엇인가요?", "우리 목장이 더 건강한 공동체가 되기 위해 바꿀 한 가지는 무엇인가요?", "이번 주 한 명에게 먼저 안부를 묻고 기도하세요."),
                PassageThemeDefinition("SHARING", 90, false, "사도행전 2:44-45", "내게 맡겨진 것을 기쁘게 나누기 어려운 이유는 무엇인가요?", "지금 공동체 안에서 함께 돌볼 필요는 무엇인가요?", "시간이나 재능으로 도울 수 있는 한 가지를 실천하세요."),
                PassageThemeDefinition("WORSHIP", 82, false, "사도행전 2:46-47", "일상에서 예배가 이어지고 있는 모습은 무엇인가요?", "함께 감사하고 찬양할 하나님의 일은 무엇인가요?", "오늘 감사 제목을 적고 하나님께 표현하세요."),
            ),
            ("EPH" to 4) to listOf(
                PassageThemeDefinition("FORGIVENESS", 98, true, "에베소서 4:31-32", "용서를 미루게 하는 상처와 두려움은 무엇인가요?", "용서와 화해를 위해 공동체의 기도가 필요한 관계가 있나요?", "상대에게 바로 연락하기 전 하나님 앞에서 용서의 첫걸음을 기록하세요."),
                PassageThemeDefinition("SPEECH", 93, false, "에베소서 4:29", "내 말이 사람을 세우기보다 무너뜨렸던 순간은 언제인가요?", "우리 모임이 서로를 세우는 말을 사용하려면 어떤 약속이 필요할까요?", "오늘 한 사람에게 구체적인 격려를 전하세요."),
                PassageThemeDefinition("RESTORATION", 84, false, "에베소서 4:31-32", "성령께서 새롭게 하시길 바라는 관계의 모습은 무엇인가요?", "관계 회복을 위해 안전하게 나눌 수 있는 작은 걸음은 무엇인가요?", "화해를 위한 다음 행동을 작고 구체적으로 정하세요."),
            ),
        )
    }
}

/** 리더가 본문 안에서 선택할 수 있는 주제 후보와 근거를 전달합니다. */
data class QuietTimePassageThemeView(
    val code: String,
    val name: String,
    val parentCode: String?,
    val description: String,
    val relevanceScore: Int,
    val primary: Boolean,
    val evidenceReference: String,
)

/** 생성 전에 미리 보거나 세션에 불변 스냅샷으로 저장할 질문 묶음입니다. */
data class QuietTimeQuestionSet(
    val focusTheme: QuietTimePassageThemeView,
    val style: QuietTimeQuestionStyle,
    val version: Int,
    val observationQuestions: List<String>,
    val meditationQuestions: List<String>,
    val sharingQuestions: List<String>,
    val actionGuide: String,
    val prayerGuide: String,
)
