package com.example.core.network

import android.util.Log
import androidx.room.withTransaction
import com.example.core.database.AyahEntity
import com.example.core.database.AyahTafsirEntity
import com.example.core.database.AyahTranslationEntity
import com.example.core.database.AudioSegmentEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.WordEntity
import com.example.core.database.WordTranslationEntity
import com.example.core.localization.BidiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class QuranSyncRepository(
    private val database: NoorDatabase,
    private val apiService: QuranComApiService
) {
    private val quranDao = database.quranDao()

    suspend fun downloadSurahForOffline(
        surahId: Int,
        translationIds: List<Int> = listOf(161, 131),
        wbwLanguages: List<String> = listOf("bn", "en"),
        reciterId: Int = 7
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val surah = quranDao.getSurah(surahId) ?: return@withContext Result.failure(
                IllegalArgumentException("Unknown Surah $surahId")
            )
            val totalPages = ((surah.ayahCount - 1) / 50) + 1
            val ayahsToSave = mutableListOf<AyahEntity>()
            val wordsToSave = mutableListOf<WordEntity>()
            val wordTranslationsToSave = mutableListOf<WordTranslationEntity>()
            val ayahTranslationsToSave = mutableListOf<AyahTranslationEntity>()
            val segmentsToSave = mutableListOf<AudioSegmentEntity>()

            val translationsCsv = translationIds.ifEmpty { listOf(161, 131) }.joinToString(",")
            val primaryWbwLang = wbwLanguages.firstOrNull() ?: "en"

            for (page in 1..totalPages) {
                val response = apiService.getVersesByChapter(
                    chapterNumber = surahId,
                    language = primaryWbwLang,
                    translations = translationsCsv,
                    audio = reciterId,
                    page = page,
                    perPage = 50
                )
                for (v in response.verses) {
                    val uthmani = v.textUthmani ?: v.words.joinToString(" ") { it.textUthmani.orEmpty() }
                    val indopak = v.textIndopak ?: uthmani
                    val tajweed = v.textTajweed ?: uthmani
                    ayahsToSave.add(
                        AyahEntity(
                            id = v.id,
                            surahId = surahId,
                            ayahNumber = v.verseNumber,
                            verseKey = v.verseKey,
                            textUthmani = uthmani,
                            textIndopak = indopak,
                            textTajweedHtml = tajweed,
                            normalizedArabic = BidiUtils.normalizeArabicForSearch(uthmani),
                            juzNumber = v.juzNumber,
                            hizbNumber = v.hizbNumber,
                            pageNumber = v.pageNumber
                        )
                    )

                    for (w in v.words) {
                        val wordTextUthmani = w.textUthmani.orEmpty()
                        if (wordTextUthmani.isBlank()) continue
                        wordsToSave.add(
                            WordEntity(
                                id = w.id,
                                ayahId = v.id,
                                position = w.position,
                                textUthmani = wordTextUthmani,
                                textIndopak = w.textIndopak ?: wordTextUthmani,
                                transliteration = w.transliteration?.text.orEmpty(),
                                rootArabic = "",
                                lemmaArabic = "",
                                grammarTagJson = "",
                                audioUrl = w.audioUrl?.let { url ->
                                    if (url.startsWith("http")) url else "https://audio.qurancdn.com/$url"
                                }
                            )
                        )
                        val meaning = w.translation?.text.orEmpty()
                        if (meaning.isNotBlank()) {
                            wordTranslationsToSave.add(
                                WordTranslationEntity(
                                    wordId = w.id,
                                    languageCode = primaryWbwLang,
                                    meaningText = meaning
                                )
                            )
                        }
                    }

                    for (tr in v.translations) {
                        val langCode = when (tr.resourceId) {
                            161, 162 -> "bn"
                            97 -> "ur"
                            33 -> "id"
                            77 -> "tr"
                            else -> "en"
                        }
                        ayahTranslationsToSave.add(
                            AyahTranslationEntity(
                                sourceId = tr.resourceId,
                                ayahId = v.id,
                                languageCode = langCode,
                                translatorName = tr.resourceName ?: "Verified Translation",
                                translatedText = cleanHtml(tr.text)
                            )
                        )
                    }

                    val audioUrl = v.audio?.url?.let { raw ->
                        if (raw.startsWith("http")) raw else "https://verses.quran.com/$raw"
                    } ?: buildFallbackAudioUrl(reciterId, surahId, v.verseNumber)

                    val segJson = JSONArray().apply {
                        v.audio?.segments?.forEach { seg ->
                            if (seg.size >= 3) {
                                put(
                                    JSONObject().apply {
                                        put("position", seg[0].toInt())
                                        put("startMs", seg[1])
                                        put("endMs", seg[2])
                                    }
                                )
                            }
                        }
                    }.toString()

                    segmentsToSave.add(
                        AudioSegmentEntity(
                            reciterId = reciterId,
                            ayahId = v.id,
                            audioUrl = audioUrl,
                            localFilePath = null,
                            wordTimestampSegmentsJson = segJson
                        )
                    )
                }
            }

            database.withTransaction {
                quranDao.insertAyahs(ayahsToSave)
                quranDao.insertWords(wordsToSave)
                quranDao.insertWordTranslations(wordTranslationsToSave)
                quranDao.insertAyahTranslations(ayahTranslationsToSave)
                quranDao.insertAudioSegments(segmentsToSave)
                quranDao.markSurahDownloaded(surahId, true)
            }
            Result.success(ayahsToSave.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("QuranSync", "Failed downloading Surah $surahId", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAyahTafsirIfMissing(
        ayahId: Int,
        verseKey: String,
        tafsirId: Int,
        languageCode: String,
        tafsirName: String
    ): Result<AyahTafsirEntity> = withContext(Dispatchers.IO) {
        try {
            val existing = quranDao.getTafsirsForAyah(ayahId).firstOrNull { it.sourceId == tafsirId }
            if (existing != null) return@withContext Result.success(existing)

            val response = apiService.getAyahTafsir(tafsirId, verseKey)
            val html = response.tafsir?.text?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Empty Tafsir response"))
            val entity = AyahTafsirEntity(
                sourceId = tafsirId,
                ayahId = ayahId,
                languageCode = languageCode,
                tafsirName = response.tafsir.resourceName ?: tafsirName,
                tafsirHtmlOrMarkdown = html
            )
            quranDao.insertAyahTafsirs(listOf(entity))
            Result.success(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("QuranSync", "Failed fetching Tafsir $tafsirId for $verseKey", e)
            Result.failure(e)
        }
    }

    suspend fun downloadTranslationPack(sourceId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val downloadedSurahs = listOf(1, 103, 108, 112, 113, 114)
            for (surahId in downloadedSurahs) {
                downloadSurahForOffline(
                    surahId = surahId,
                    translationIds = listOf(sourceId)
                )
            }
            quranDao.setTranslationDownloaded(sourceId, true)
            quranDao.setTranslationEnabled(sourceId, true)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("QuranSync", "Failed downloading translation pack $sourceId", e)
            Result.failure(e)
        }
    }

    private fun buildFallbackAudioUrl(reciterId: Int, surahId: Int, ayahNumber: Int): String {
        val folder = when (reciterId) {
            1 -> "Husary_128kbps"
            9 -> "Minshawy_Murattal_128kbps"
            else -> "Alafasy_128kbps"
        }
        val s = surahId.toString().padStart(3, '0')
        val a = ayahNumber.toString().padStart(3, '0')
        return "https://everyayah.com/data/$folder/$s$a.mp3"
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<sup[^>]*>.*?</sup>"), "")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }
}
