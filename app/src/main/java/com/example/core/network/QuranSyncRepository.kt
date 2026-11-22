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
                            161, 162, 163 -> "bn"
                            97, 54, 158, 234 -> "ur"
                            33, 134 -> "id"
                            77, 52 -> "tr"
                            31 -> "fr"
                            83 -> "es"
                            122 -> "hi"
                            27 -> "de"
                            39 -> "ms"
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

                    val reciter = quranDao.getReciter(reciterId)
                    val audioUrl = v.audio?.url?.let { raw ->
                        if (raw.startsWith("http")) raw else "https://verses.quran.com/$raw"
                    } ?: buildFallbackAudioUrl(reciter?.baseUrl, reciterId, surahId, v.verseNumber)

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
                // Preserve richer root/lemma/grammar metadata if starter words already exist
                val existingWords = ayahsToSave.flatMap { quranDao.getWordsForAyah(it.id) }.associateBy { it.id }
                val mergedWords = wordsToSave.map { w ->
                    val old = existingWords[w.id]
                    if (old != null) {
                        w.copy(
                            rootArabic = old.rootArabic,
                            lemmaArabic = old.lemmaArabic,
                            grammarTagJson = old.grammarTagJson
                        )
                    } else w
                }
                quranDao.insertWords(mergedWords)
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
                ?: return@withContext Result.failure(IllegalStateException("Tafsir not available on API for $verseKey"))
            val entity = AyahTafsirEntity(
                sourceId = tafsirId,
                ayahId = ayahId,
                languageCode = languageCode,
                tafsirName = response.tafsir.resourceName ?: tafsirName,
                tafsirHtmlOrMarkdown = html
            )
            quranDao.insertAyahTafsirs(listOf(entity))
            quranDao.updateTafsirSourceStatus(tafsirId, downloaded = true, enabled = true)
            Result.success(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("QuranSync", "Failed fetching Tafsir $tafsirId for $verseKey", e)
            Result.failure(e)
        }
    }

    suspend fun refreshRemoteTafsirCatalog(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val existingMap = quranDao.getAllTafsirSources().associateBy { it.id }
            val remote = apiService.getTafsirResources("en").tafsirs
            val entities = remote.map { item ->
                val prev = existingMap[item.id]
                val langCode = mapLanguageNameToCode(item.languageName)
                com.example.core.database.TafsirSourceEntity(
                    id = item.id,
                    languageCode = langCode,
                    languageName = item.languageName ?: langCode.uppercase(),
                    tafsirName = item.name,
                    isDownloaded = prev?.isDownloaded ?: false,
                    isEnabled = prev?.isEnabled ?: false,
                    fileSizeKb = prev?.fileSizeKb ?: 7500
                )
            }
            if (entities.isNotEmpty()) {
                quranDao.insertTafsirSources(entities)
            }
            Result.success(entities.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("QuranSync", "Could not refresh remote tafsir catalog", e)
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

    private fun mapLanguageNameToCode(languageName: String?): String {
        return when (languageName?.lowercase()?.trim()) {
            "bengali", "bangla" -> "bn"
            "arabic" -> "ar"
            "english" -> "en"
            "urdu" -> "ur"
            "indonesian" -> "id"
            "turkish" -> "tr"
            "russian" -> "ru"
            "kurdish" -> "ku"
            "french" -> "fr"
            "spanish" -> "es"
            "hindi" -> "hi"
            "malay" -> "ms"
            "german" -> "de"
            else -> languageName?.take(2)?.lowercase() ?: "en"
        }
    }

    private fun buildFallbackAudioUrl(baseUrl: String?, reciterId: Int, surahId: Int, ayahNumber: Int): String {
        val resolvedBase = baseUrl?.takeIf { it.isNotBlank() } ?: when (reciterId) {
            1 -> "https://everyayah.com/data/Husary_128kbps/"
            9 -> "https://everyayah.com/data/Minshawy_Murattal_128kbps/"
            else -> "https://everyayah.com/data/Alafasy_128kbps/"
        }
        val normalizedBase = if (resolvedBase.endsWith("/")) resolvedBase else "$resolvedBase/"
        val s = surahId.toString().padStart(3, '0')
        val a = ayahNumber.toString().padStart(3, '0')
        return "${normalizedBase}${s}${a}.mp3"
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<sup[^>]*>.*?</sup>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
    }
}
