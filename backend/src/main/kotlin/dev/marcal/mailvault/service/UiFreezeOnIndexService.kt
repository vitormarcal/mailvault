package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.AppMetaRepository
import org.springframework.stereotype.Service

@Service
class UiFreezeOnIndexService(
    private val appMetaRepository: AppMetaRepository,
) {
    fun isEnabled(): Boolean = normalize(appMetaRepository.get(KEY_FREEZE_ON_INDEX))

    fun setEnabled(enabled: Boolean?): Boolean {
        val normalized = enabled == true
        appMetaRepository.put(KEY_FREEZE_ON_INDEX, normalized.toString())
        return normalized
    }

    private fun normalize(value: String?): Boolean {
        val cleaned = value?.trim()?.lowercase()
        return cleaned == "true"
    }

    private companion object {
        const val KEY_FREEZE_ON_INDEX = "ui.freezeOnIndex"
    }
}
