package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.MessageDetailResponse
import dev.marcal.mailvault.api.MessagesListResponse
import dev.marcal.mailvault.api.HtmlRenderResponse
import dev.marcal.mailvault.api.AttachmentResponse
import dev.marcal.mailvault.service.AttachmentService
import dev.marcal.mailvault.service.HtmlRenderService
import dev.marcal.mailvault.service.MessageQueryService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/messages")
class MessagesController(
    private val messageQueryService: MessageQueryService,
    private val htmlRenderService: HtmlRenderService,
    private val attachmentService: AttachmentService,
) {
    @GetMapping
    fun list(
        @RequestParam query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): MessagesListResponse = messageQueryService.list(query = query, page = page, size = size)

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): MessageDetailResponse = messageQueryService.detail(id)

    @GetMapping("/{id}/render")
    fun render(@PathVariable id: String): HtmlRenderResponse = HtmlRenderResponse(html = htmlRenderService.render(id))

    @GetMapping("/{id}/attachments")
    fun attachments(@PathVariable id: String): List<AttachmentResponse> = attachmentService.listByMessage(id)

    @GetMapping("/{id}/cid/{cid}")
    fun cid(
        @PathVariable id: String,
        @PathVariable cid: String,
    ): ResponseEntity<ByteArray> {
        val file = attachmentService.resolveInlineCid(id, cid)
        val mediaType =
            runCatching { MediaType.parseMediaType(file.contentType) }
                .getOrElse { MediaType.APPLICATION_OCTET_STREAM }

        val dispositionName = file.filename?.ifBlank { "inline" } ?: "inline"

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$dispositionName\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.bytes)
    }
}
