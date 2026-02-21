package dev.marcal.mailvault.controller

import dev.marcal.mailvault.api.MessageDetailResponse
import dev.marcal.mailvault.api.MessagesListRequest
import dev.marcal.mailvault.api.MessagesListResponse
import dev.marcal.mailvault.repository.MessageRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/messages")
class MessagesController(
    private val messageRepository: MessageRepository,
) {
    @GetMapping
    fun list(
        @RequestParam query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): MessagesListResponse {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0")
        }
        if (size <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be > 0")
        }

        val request = MessagesListRequest(query = query, page = page, size = size)
        val result = messageRepository.list(request.query, request.page, request.size)
        return MessagesListResponse(
            page = request.page,
            size = request.size,
            total = result.total,
            items = result.items,
        )
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): MessageDetailResponse =
        messageRepository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "message not found")
}
