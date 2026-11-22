package com.example.core.firebase

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.core.database.HabitStreakEntity
import com.example.core.database.ReadingProgressEntity
import com.example.core.database.UserBookmarkEntity
import com.example.core.database.UserCollectionEntity
import com.example.core.database.UserNoteEntity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

object GoogleAuthHelper {

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activityContext = context as? Activity ?: context
                val cred = credentialManager.getCredential(activityContext, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type returned.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Sign in failed")
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Auth", "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}

class FirestoreSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun sanitizeDocId(raw: String): String {
        return raw.replace(":", "_").replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
    }

    fun observeCloudBookmarks(userId: String): Flow<List<CloudBookmark>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch." }
        val path = "users/$uid/bookmarks"
        emitAll(
            db.collection("users").document(uid).collection("bookmarks")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CloudBookmark::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapFirestoreStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeCloudNotes(userId: String): Flow<List<CloudNote>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = "users/$uid/notes"
        emitAll(
            db.collection("users").document(uid).collection("notes")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CloudNote::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapFirestoreStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeCloudCollections(userId: String): Flow<List<CloudCollection>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = "users/$uid/collections"
        emitAll(
            db.collection("users").document(uid).collection("collections")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CloudCollection::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    unwrapFirestoreStreamError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    suspend fun upsertBookmark(bookmark: UserBookmarkEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(bookmark.id)
        val docRef = db.collection("users").document(uid).collection("bookmarks").document(docId)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "collectionId" to bookmark.collectionId.take(64),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                bookmark.label?.take(120)?.let { updates["label"] = it }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "verseKey" to bookmark.verseKey.take(16),
                    "surahId" to bookmark.surahId.coerceIn(1, 114),
                    "ayahNumber" to bookmark.ayahNumber.coerceIn(1, 286),
                    "collectionId" to bookmark.collectionId.take(64),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                bookmark.label?.take(120)?.let { payload["label"] = it }
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteBookmark(bookmarkId: String): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(bookmarkId)
        val docRef = db.collection("users").document(uid).collection("bookmarks").document(docId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertCollection(collection: UserCollectionEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(collection.id)
        val docRef = db.collection("users").document(uid).collection("collections").document(docId)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "name" to collection.name.take(80).ifBlank { "Collection" },
                        "colorHex" to collection.colorHex.take(12),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "name" to collection.name.take(80).ifBlank { "Collection" },
                        "colorHex" to collection.colorHex.take(12),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertNote(note: UserNoteEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(note.id)
        val docRef = db.collection("users").document(uid).collection("notes").document(docId)
        return try {
            val trimmed = note.noteText.trim().take(4000)
            require(trimmed.isNotEmpty()) { "Note text cannot be empty." }
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "noteText" to trimmed,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "verseKey" to note.verseKey.take(16),
                        "surahId" to note.surahId.coerceIn(1, 114),
                        "ayahNumber" to note.ayahNumber.coerceIn(1, 286),
                        "noteText" to trimmed,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteNote(noteId: String): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(noteId)
        val docRef = db.collection("users").document(uid).collection("notes").document(docId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertProgress(progress: ReadingProgressEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docRef = db.collection("users").document(uid).collection("progress").document("main")
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "lastSurahId" to progress.lastSurahId.coerceIn(1, 114),
                        "lastAyahNumber" to progress.lastAyahNumber.coerceIn(1, 286),
                        "lastVerseKey" to progress.lastVerseKey.take(16),
                        "dailyAyahGoal" to progress.dailyAyahGoal.coerceIn(1, 1000),
                        "khatamTargetDays" to progress.khatamTargetDays.coerceIn(1, 3650),
                        "khatamCompletedAyahs" to progress.khatamCompletedAyahs.coerceIn(0, 6236),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "lastSurahId" to progress.lastSurahId.coerceIn(1, 114),
                        "lastAyahNumber" to progress.lastAyahNumber.coerceIn(1, 286),
                        "lastVerseKey" to progress.lastVerseKey.take(16),
                        "dailyAyahGoal" to progress.dailyAyahGoal.coerceIn(1, 1000),
                        "khatamTargetDays" to progress.khatamTargetDays.coerceIn(1, 3650),
                        "khatamCompletedAyahs" to progress.khatamCompletedAyahs.coerceIn(0, 6236),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertStreak(streak: HabitStreakEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val docId = sanitizeDocId(streak.dateKey)
        val docRef = db.collection("users").document(uid).collection("streaks").document(docId)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "ayahsReadCount" to streak.ayahsReadCount.coerceIn(0, 6236),
                        "minutesSpent" to streak.minutesSpent.coerceIn(0, 1440),
                        "tasbeehCount" to streak.tasbeehCount.coerceIn(0, 100000),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "dateKey" to streak.dateKey,
                        "ayahsReadCount" to streak.ayahsReadCount.coerceIn(0, 6236),
                        "minutesSpent" to streak.minutesSpent.coerceIn(0, 1440),
                        "tasbeehCount" to streak.tasbeehCount.coerceIn(0, 100000),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    private fun unwrapFirestoreStreamError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
