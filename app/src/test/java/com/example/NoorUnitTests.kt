package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.core.audio.HifzLoopConfig
import com.example.core.audio.HifzLoopEngine
import com.example.core.audio.LoopAdvanceResult
import com.example.core.audio.WordAudioSegment
import com.example.core.database.HabitStreakEntity
import com.example.core.database.WordEntity
import com.example.core.database.WordTranslationEntity
import com.example.core.ibadah.PrayerTimeCalculator
import com.example.core.ibadah.StreakCalculator
import com.example.core.localization.BidiUtils
import com.example.core.localization.SegmentRoleType
import com.example.core.localization.WordMorphologyAnalyzer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoorUnitTests {

    // 1. Arabic-Bangla Bidi formatting & normalization tests
    @Test
    fun testArabicBanglaBidiWrappingAndDigits() {
        val bnLocale = Locale.forLanguageTag("bn")
        val wrappedArabic = BidiUtils.wrapArabicInline("بِسْمِ ٱللَّهِ", bnLocale)
        // AndroidX BidiFormatter isolates RTL text inside LTR Bangla context with LRM (\u200E) + RLE (\u202B) / PDF (\u202C)
        assertTrue(wrappedArabic.contains("بِسْمِ"))
        assertTrue(
            "Expected Bidi isolation markers in '$wrappedArabic'",
            wrappedArabic.contains("\u202B") || wrappedArabic.contains("\u2067") || wrappedArabic.contains("\u200E")
        )

        val wrappedKey = BidiUtils.wrapVerseKey("2:255", Locale.forLanguageTag("ar"))
        assertTrue(wrappedKey.contains("2:255"))
        assertTrue(wrappedKey.length > "2:255".length)

        // Localized numbers
        assertEquals("২৫৫", BidiUtils.formatNumber(255, "bn"))
        assertEquals("٢٥٥", BidiUtils.formatNumber(255, "ar"))
        assertEquals("255", BidiUtils.formatNumber(255, "en"))

        // Ornate Ayah marker
        val marker = BidiUtils.formatAyahMarker(7, useArabicDigits = true)
        assertEquals("\uFD3F٧\uFD3E", marker)

        // Harakat stripping for search
        val normalized = BidiUtils.normalizeArabicForSearch("بِسْمِ اللَّهِ الرَّحْمَنِ")
        assertEquals("بسم الله الرحمن", normalized)
    }

    // 2. Ayah loop range logic & word segment timestamp tests
    @Test
    fun testHifzAyahLoopAndWordTimestamps() {
        // Repeat each ayah 3 times before advancing
        val repeatConfig = HifzLoopConfig(startAyah = 1, endAyah = 5, repeatPerAyah = 3, pauseSecondsAfterAyah = 2)
        val step1 = HifzLoopEngine.nextStep(currentAyah = 2, currentIteration = 1, config = repeatConfig, maxAyahInSurah = 7)
        assertEquals(LoopAdvanceResult.PlayAyah(ayahNumber = 2, iterationOfAyah = 2, delayMsBeforePlay = 2000L), step1)

        val step2 = HifzLoopEngine.nextStep(currentAyah = 2, currentIteration = 3, config = repeatConfig, maxAyahInSurah = 7)
        assertEquals(LoopAdvanceResult.PlayAyah(ayahNumber = 3, iterationOfAyah = 1, delayMsBeforePlay = 2000L), step2)

        // A-B Loop wrapping back to A when B ends (also handles inverted start > end safely)
        val loopConfig = HifzLoopConfig(startAyah = 5, endAyah = 3, repeatPerAyah = 1, loopRangeEnabled = true)
        val wrapStep = HifzLoopEngine.nextStep(currentAyah = 5, currentIteration = 1, config = loopConfig, maxAyahInSurah = 7)
        assertEquals(LoopAdvanceResult.PlayAyah(ayahNumber = 3, iterationOfAyah = 1, delayMsBeforePlay = 0L), wrapStep)

        // Non-loop completes at end of Surah
        val noLoopConfig = HifzLoopConfig(repeatPerAyah = 1, loopRangeEnabled = false)
        val doneStep = HifzLoopEngine.nextStep(currentAyah = 7, currentIteration = 1, config = noLoopConfig, maxAyahInSurah = 7)
        assertEquals(LoopAdvanceResult.Completed, doneStep)

        // Infinite repeat stays on current Ayah
        val infConfig = HifzLoopConfig(repeatPerAyah = 0)
        val infStep = HifzLoopEngine.nextStep(currentAyah = 4, currentIteration = 9, config = infConfig, maxAyahInSurah = 7)
        assertEquals(LoopAdvanceResult.PlayAyah(ayahNumber = 4, iterationOfAyah = 10, delayMsBeforePlay = 0L), infStep)

        // Word timestamp resolution
        val segments = listOf(
            WordAudioSegment(1, 0L, 450L),
            WordAudioSegment(2, 460L, 1100L),
            WordAudioSegment(3, 1120L, 1800L)
        )
        assertEquals(1, HifzLoopEngine.findActiveWordPosition(segments, 220L))
        assertEquals(2, HifzLoopEngine.findActiveWordPosition(segments, 800L))
        assertEquals(3, HifzLoopEngine.findActiveWordPosition(segments, 1500L))
        assertNull(HifzLoopEngine.findActiveWordPosition(emptyList(), 500L))
    }

    // 3. Daily streak calculation & Khatam pacing tests
    @Test
    fun testDailyStreakAndKhatamCalculations() {
        val today = "2026-11-21"
        val consecutive = listOf(
            HabitStreakEntity(dateKey = "2026-11-21", ayahsReadCount = 12),
            HabitStreakEntity(dateKey = "2026-11-20", ayahsReadCount = 20),
            HabitStreakEntity(dateKey = "2026-11-19", ayahsReadCount = 8),
            HabitStreakEntity(dateKey = "2026-11-18", ayahsReadCount = 0), // gap breaks streak
            HabitStreakEntity(dateKey = "2026-11-17", ayahsReadCount = 15)
        )
        assertEquals(3, StreakCalculator.calculateCurrentStreak(consecutive, today))

        // If today has 0 so far, active streak carries from yesterday
        val carryOver = listOf(
            HabitStreakEntity(dateKey = "2026-11-20", ayahsReadCount = 10),
            HabitStreakEntity(dateKey = "2026-11-19", ayahsReadCount = 10)
        )
        assertEquals(2, StreakCalculator.calculateCurrentStreak(carryOver, today))

        // Khatam plan math (6236 total Ayahs, 236 done -> 6000 remaining over 30 days -> 200/day)
        val khatam = StreakCalculator.calculateKhatamPlan(completedAyahs = 236, targetDays = 30)
        assertEquals(6000, khatam.remainingAyahs)
        assertEquals(200, khatam.requiredAyahsPerDay)

        // Qibla bearing from Dhaka (23.81, 90.41) toward Makkah (21.42, 39.82) points West-Southwest (~277 deg)
        val dhakaQibla = PrayerTimeCalculator.calculateQiblaBearing(23.8103, 90.4125)
        assertTrue("Expected WSW bearing ~277°, got $dhakaQibla", dhakaQibla in 265f..288f)
    }

    // 4. Word Morphology Inspector & Starter JSON Qari/Tafsir catalog integrity
    @Test
    fun testWordMorphologyAndStarterCatalogCounts() {
        val word = WordEntity(
            id = 101L,
            ayahId = 1,
            position = 1,
            textUthmani = "بِسْمِ",
            textIndopak = "بِسْمِ",
            transliteration = "Bismi",
            rootArabic = "س م و",
            lemmaArabic = "اِسْم",
            grammarTagJson = "Preposition + Noun, Genitive",
            audioUrl = "https://audio.qurancdn.com/wbw/001_001_001.mp3"
        )
        val translations = listOf(
            WordTranslationEntity(101L, "en", "In the name of"),
            WordTranslationEntity(101L, "bn", "নামে"),
            WordTranslationEntity(101L, "ur", "نام سے")
        )
        val analysis = WordMorphologyAnalyzer.analyze(word, translations)
        assertEquals("Preposition + Noun", analysis.partOfSpeechEn)
        assertTrue(analysis.grammaticalCaseOrMood.contains("Genitive"))
        assertTrue(analysis.segments.any { it.roleType == SegmentRoleType.PREFIX && it.arabicPrefixOrPart == "بِـ" })
        assertTrue(analysis.segments.any { it.roleType == SegmentRoleType.STEM })
        assertEquals("বাংলা (Bangla)", analysis.allMeanings.first().first)
        assertEquals(3, analysis.allMeanings.size)

        // Verify starter catalog contains >= 50 Qaris, >= 15 Tafsirs, >= 12 Translations
        val appContext = ApplicationProvider.getApplicationContext<android.app.Application>()
        val starterText = appContext.assets.open("quran_starter/quran_starter.json").bufferedReader().use { it.readText() }
        val root = JSONObject(starterText)
        assertTrue("Expected >= 50 reciters", root.getJSONArray("reciters").length() >= 50)
        assertTrue("Expected >= 15 tafsir sources", root.getJSONArray("tafsirSources").length() >= 15)
        assertTrue("Expected >= 12 translation sources", root.getJSONArray("translationSources").length() >= 12)
    }
}
