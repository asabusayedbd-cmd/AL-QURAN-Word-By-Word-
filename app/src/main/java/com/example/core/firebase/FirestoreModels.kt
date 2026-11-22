package com.example.core.firebase

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class CloudBookmark(
    @DocumentId val id: String = "",
    val userId: String = "",
    val verseKey: String = "",
    val surahId: Long = 1L,
    val ayahNumber: Long = 1L,
    val collectionId: String = "favorites",
    val label: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudCollection(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val colorHex: String = "#0F5C4D",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudNote(
    @DocumentId val id: String = "",
    val userId: String = "",
    val verseKey: String = "",
    val surahId: Long = 1L,
    val ayahNumber: Long = 1L,
    val noteText: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudProgress(
    @DocumentId val id: String = "",
    val userId: String = "",
    val lastSurahId: Long = 1L,
    val lastAyahNumber: Long = 1L,
    val lastVerseKey: String = "1:1",
    val dailyAyahGoal: Long = 20L,
    val khatamTargetDays: Long = 30L,
    val khatamCompletedAyahs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudStreak(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateKey: String = "",
    val ayahsReadCount: Long = 0L,
    val minutesSpent: Long = 0L,
    val tasbeehCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
