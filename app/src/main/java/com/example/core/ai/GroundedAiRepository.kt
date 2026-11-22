package com.example.core.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

sealed interface AiStudyUiState {
    data object Idle : AiStudyUiState
    data object Loading : AiStudyUiState
    data class Streaming(val text: String) : AiStudyUiState
    data class Success(val text: String) : AiStudyUiState
    data class Error(val message: String) : AiStudyUiState
}

data class SemanticSearchExpansion(
    val keywords: List<String>,
    val verseKeys: List<String>,
    val summaryHint: String
)

class GroundedAiRepository {

    private val studyModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    """
                    You are a scholarly, respectful Quran Study Assistant inside Noor Al-Quran.
                    STRICT INTEGRITY RULES:
                    1. NEVER reconstruct, invent, vowelize, or alter Quranic Arabic verses from memory. Quote ONLY the verified Arabic/translation/Tafsir text explicitly provided in the user prompt context.
                    2. Ground EVERY claim strictly in the provided verified Ayah translations and loaded Tafsir passages.
                    3. Include explicit citations in square brackets like [Surah Al-Fatihah 1:5] or [2:255].
                    4. If the user's question asks for a legal/fiqh ruling or information outside the supplied Tafsir context, politely state that this goes beyond the loaded Tafsir passage and do not fabricate external rulings.
                    5. Respond clearly in the requested UI language (e.g., Bangla, English, Arabic).
                    """.trimIndent()
                )
            }
        )
    }

    private val semanticSearchModel by lazy {
        val searchSchema = Schema.obj(
            properties = mapOf(
                "keywords" to Schema.array(
                    items = Schema.string(),
                    description = "Relevant Arabic, Bangla, or English search terms found in Quran translations"
                ),
                "verseKeys" to Schema.array(
                    items = Schema.string(),
                    description = "Verified Surah:Ayah keys (e.g., 2:255, 1:5, 103:3, 2:201, 3:8) matching the user's topic"
                ),
                "summaryHint" to Schema.string(
                    description = "One-sentence explanation of the matched themes in the user's UI language"
                )
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = searchSchema
            }
        )
    }

    fun askAboutAyahStream(
        surahName: String,
        verseKey: String,
        arabicText: String,
        verifiedTranslations: List<String>,
        loadedTafsirExcerpts: List<String>,
        userQuestion: String,
        uiLanguageName: String
    ): Flow<AiStudyUiState> = flow {
        emit(AiStudyUiState.Loading)
        val prompt = buildString {
            appendLine("UI Language to reply in: $uiLanguageName")
            appendLine("Selected Ayah: [$surahName $verseKey]")
            appendLine("Verified Arabic Text: $arabicText")
            appendLine("Verified Translations:")
            verifiedTranslations.forEach { appendLine("- $it") }
            appendLine("Loaded Authentic Tafsir Context:")
            if (loadedTafsirExcerpts.isEmpty()) {
                appendLine("- (No Tafsir excerpt loaded for this Ayah; rely strictly on the verified translation above.)")
            } else {
                loadedTafsirExcerpts.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("User Question: $userQuestion")
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            studyModel.generateContentStream(prompt).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                if (chunkText.isNotEmpty()) {
                    accumulated.append(chunkText)
                    emit(AiStudyUiState.Streaming(accumulated.toString()))
                }
            }
        }

        val finalText = accumulated.toString().trim()
        if (finalText.isNotEmpty()) {
            emit(AiStudyUiState.Success(finalText))
        } else {
            emit(AiStudyUiState.Error("The AI Study Helper returned an empty response."))
        }
    }.catch { e ->
        when (e) {
            is TimeoutCancellationException -> {
                Log.e(TAG, "AI stream timed out", e)
                emit(AiStudyUiState.Error("Request timed out after 60 seconds. Please try again."))
            }
            is CancellationException -> throw e
            else -> {
                Log.e(TAG, "AI stream failed", e)
                emit(AiStudyUiState.Error(e.localizedMessage ?: "AI Study Helper failed."))
            }
        }
    }

    suspend fun expandSemanticQuery(
        query: String,
        uiLanguageName: String
    ): Result<SemanticSearchExpansion> {
        return try {
            val prompt = """
                Expand this Quran search query into verified search keywords and Surah:Ayah keys.
                User Query: "$query"
                UI Language: $uiLanguageName
                Do NOT invent verses; output only standard Surah:Ayah keys (like 2:255, 1:1, 103:3, 112:1, 2:201, 3:8) and short translation keywords.
            """.trimIndent()
            val response = withTimeout(30_000L) {
                semanticSearchModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty semantic search response.")
            val obj = JSONObject(rawJson)
            val keywordsArr = obj.optJSONArray("keywords")
            val keysArr = obj.optJSONArray("verseKeys")
            val keywords = buildList {
                if (keywordsArr != null) {
                    for (i in 0 until keywordsArr.length()) add(keywordsArr.getString(i))
                }
            }
            val verseKeys = buildList {
                if (keysArr != null) {
                    for (i in 0 until keysArr.length()) add(keysArr.getString(i))
                }
            }
            Result.success(
                SemanticSearchExpansion(
                    keywords = keywords,
                    verseKeys = verseKeys,
                    summaryHint = obj.optString("summaryHint")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Semantic search failed", e)
            Result.failure(e)
        }
    }

    suspend fun suggestHifzSchedule(
        currentStreakDays: Int,
        todayAyahs: Int,
        dailyGoal: Int,
        khatamDays: Int,
        uiLanguageName: String
    ): Result<String> {
        return try {
            val prompt = """
                Reply in $uiLanguageName (concise, warm, 3-4 bullet points).
                User Quran habit metrics:
                - Current streak: $currentStreakDays days
                - Ayahs read today: $todayAyahs / $dailyGoal
                - Khatam target: $khatamDays days
                Suggest a gentle, realistic daily reading & Hifz revision schedule without fabricating Quranic texts.
            """.trimIndent()
            val response = withTimeout(30_000L) {
                studyModel.generateContent(prompt)
            }
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) Result.failure(IllegalStateException("Empty schedule suggestion."))
            else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Schedule suggestion failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "GroundedAiRepo"
    }
}
