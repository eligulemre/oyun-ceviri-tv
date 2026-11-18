package com.metallic.chiaki.translate

import android.content.Context
import androidx.preference.PreferenceManager

class TranslationPrefs(context: Context)
{
	companion object
	{
		const val KEY_ENABLED = "translation_enabled"
		const val KEY_API_KEY = "translation_anthropic_key"
		const val KEY_MODEL = "translation_model"
		const val KEY_REGION_TOP = "translation_region_top"
		const val KEY_TEXT_SIZE = "translation_text_size"
		const val KEY_HIDE_ORIGINAL = "translation_hide_original"
		const val DEFAULT_MODEL = "claude-haiku-5-5"
	}

	private val sp = PreferenceManager.getDefaultSharedPreferences(context)

	val enabled get() = sp.getBoolean(KEY_ENABLED, true)
	val apiKey get() = sp.getString(KEY_API_KEY, "")?.trim().orEmpty()
	val model get() = sp.getString(KEY_MODEL, DEFAULT_MODEL)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_MODEL
	/** Altyazı bandının başladığı yükseklik (% olarak, üstten). GTA altyazıları ekranın alt kısmında. */
	val regionTopPercent get() = sp.getInt(KEY_REGION_TOP, 70).coerceIn(30, 95)
	val textSizeSp get() = sp.getInt(KEY_TEXT_SIZE, 26).coerceIn(14, 48)
	val hideOriginal get() = sp.getBoolean(KEY_HIDE_ORIGINAL, true)
}
