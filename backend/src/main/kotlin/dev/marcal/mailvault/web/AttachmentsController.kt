package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.AttachmentService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/attachments")
class AttachmentsController(
    private val attachmentService: AttachmentService,
) {
    @GetMapping("/{attachmentId}/download")
    fun download(
        @PathVariable attachmentId: String,
    ): ResponseEntity<ByteArray> {
        val file = attachmentService.resolveDownload(attachmentId)
        val mediaType =
            runCatching { MediaType.parseMediaType(file.contentType) }
                .getOrElse { MediaType.APPLICATION_OCTET_STREAM }

        val dispositionName = file.filename?.ifBlank { "attachment" } ?: "attachment"

        return ResponseEntity
            .ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$dispositionName\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.bytes)
    }
}
