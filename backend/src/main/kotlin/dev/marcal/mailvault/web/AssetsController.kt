package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.AssetFileService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class AssetsController(
    private val assetFileService: AssetFileService,
) {
    @GetMapping("/assets/{messageId}/{filename}")
    fun asset(
        @PathVariable messageId: String,
        @PathVariable filename: String,
    ): ResponseEntity<ByteArray> {
        val file = assetFileService.resolveDownloadedAsset(messageId, filename)
        val mediaType =
            runCatching { MediaType.parseMediaType(file.contentType) }
                .getOrElse { MediaType.APPLICATION_OCTET_STREAM }

        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.filename}\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.bytes)
    }
}
