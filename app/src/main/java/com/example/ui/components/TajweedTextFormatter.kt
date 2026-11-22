package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.example.ui.theme.TajweedPalette

object TajweedTextFormatter {

    private val TAJWEED_TAG_REGEX = Regex("<tajweed\\s+class=([a-zA-Z0-9_\\-]+)>(.*?)</tajweed>")
    private val ANY_HTML_TAG_REGEX = Regex("<[^>]+>")

    fun formatArabicVerse(
        rawText: String,
        tajweedHtml: String,
        tajweedEnabled: Boolean,
        palette: TajweedPalette,
        ayahEndMarker: String = ""
    ): AnnotatedString {
        if (!tajweedEnabled || !tajweedHtml.contains("<tajweed")) {
            return buildAnnotatedString {
                append(rawText)
                if (ayahEndMarker.isNotEmpty()) {
                    append("  ")
                    pushStyle(SpanStyle(color = palette.madd))
                    append(ayahEndMarker)
                    pop()
                }
            }
        }

        return buildAnnotatedString {
            var lastIndex = 0
            val matches = TAJWEED_TAG_REGEX.findAll(tajweedHtml)
            for (match in matches) {
                val start = match.range.first
                if (start > lastIndex) {
                    val plain = ANY_HTML_TAG_REGEX.replace(tajweedHtml.substring(lastIndex, start), "")
                    append(plain)
                }
                val ruleClass = match.groupValues[1].lowercase()
                val innerText = ANY_HTML_TAG_REGEX.replace(match.groupValues[2], "")
                val color = mapRuleToColor(ruleClass, palette)
                if (color != null) {
                    pushStyle(SpanStyle(color = color))
                    append(innerText)
                    pop()
                } else {
                    append(innerText)
                }
                lastIndex = match.range.last + 1
            }
            if (lastIndex < tajweedHtml.length) {
                val tail = ANY_HTML_TAG_REGEX.replace(tajweedHtml.substring(lastIndex), "")
                append(tail)
            }
            if (ayahEndMarker.isNotEmpty()) {
                append("  ")
                pushStyle(SpanStyle(color = palette.ghunnah))
                append(ayahEndMarker)
                pop()
            }
        }
    }

    private fun mapRuleToColor(ruleClass: String, palette: TajweedPalette): Color? = when {
        ruleClass.contains("ghunnah") -> palette.ghunnah
        ruleClass.contains("ikhfa") -> palette.ikhfa
        ruleClass.contains("idgham") -> palette.idgham
        ruleClass.contains("qalqalah") -> palette.qalqalah
        ruleClass.contains("madda") || ruleClass.contains("madd") -> palette.madd
        ruleClass.contains("iqlab") -> palette.iqlab
        ruleClass.contains("ham_wasl") || ruleClass.contains("slnt") || ruleClass.contains("laam_shamsiyah") -> palette.silent
        else -> null
    }
}
