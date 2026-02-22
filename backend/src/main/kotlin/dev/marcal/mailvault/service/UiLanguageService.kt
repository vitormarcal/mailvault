package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.AppMetaRepository
import org.springframework.stereotype.Service

@Service
class UiLanguageService(
    private val appMetaRepository: AppMetaRepository,
) {
    fun getLanguage(): String = normalize(appMetaRepository.get(KEY_UI_LANGUAGE))

    fun setLanguage(language: String?): String {
        val normalized = normalize(language)
        appMetaRepository.put(KEY_UI_LANGUAGE, normalized)
        return normalized
    }

    private fun normalize(language: String?): String {
        val cleaned = language?.trim()
        return if (cleaned != null && ALLOWED_LANGUAGES.contains(cleaned)) cleaned else DEFAULT_LANGUAGE
    }

    private companion object {
        const val KEY_UI_LANGUAGE = "ui.language"
        const val DEFAULT_LANGUAGE = "en"
        val ALLOWED_LANGUAGES = setOf("en", "pt-BR")
    }
}
