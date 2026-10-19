package com.meldiq.android.data.repo

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates received messages on this phone (2026-10-17, docs/LANGUAGES.md › Translation), with ML Kit. The message
 * text is never sent anywhere: the language is identified and the text translated on the device. The first time a
 * language pair is used, ML Kit downloads its model (about 30 MB per language) from Google, so the person is asked
 * first ([consented]). The stored, encrypted message is never changed; a translation lives only on screen.
 */
class MessageTranslator(context: Context) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)

    val consented: Boolean get() = prefs.getBoolean("translateConsent", false)
    fun consent() = prefs.edit().putBoolean("translateConsent", true).apply()

    /** Languages Meldiq offers to translate into (its interface languages). */
    val targets = listOf("en", "zh", "bn")
    var lastTarget: String
        get() = prefs.getString("translateTarget", null)?.takeIf { it in targets } ?: "en"
        set(v) { prefs.edit().putString("translateTarget", v).apply() }

    class Failure(message: String) : Exception(message)

    suspend fun translate(text: String, target: String): String {
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: throw Failure("That language isn't available for translation.")
        val code = LanguageIdentification.getClient().identifyLanguage(text).await()
        if (code == "und") throw Failure("Couldn't tell which language this message is in.")
        val src = TranslateLanguage.fromLanguageTag(code) ?: throw Failure("Translating from this language isn't available.")
        if (src == tgt) throw Failure("This message is already in that language.")
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        try {
            withTimeout(120_000) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            return translator.translate(text).await().ifBlank { throw Failure("The translation came back empty.") }
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure("Couldn't translate: the language model isn't on this phone and couldn't be downloaded.")
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
