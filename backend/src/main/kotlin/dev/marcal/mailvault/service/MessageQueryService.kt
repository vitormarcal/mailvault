package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.MessageDetailResponse
import dev.marcal.mailvault.api.MessageSummaryResponse
import dev.marcal.mailvault.api.MessagesListResponse
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import dev.marcal.mailvault.util.ValidationException
import org.springframework.stereotype.Service

@Service
class MessageQueryService(
    private val messageRepository: MessageRepository,
) {
    fun list(query: String?, page: Int, size: Int): MessagesListResponse {
        if (page < 0) {
            throw ValidationException("page must be >= 0")
        }
        if (size <= 0) {
            throw ValidationException("size must be > 0")
        }

        val result = messageRepository.list(query, page, size)
        return MessagesListResponse(
            page = page,
            size = size,
            total = result.total,
            items = result.items.map {
                MessageSummaryResponse(
                    id = it.id,
                    dateRaw = it.dateRaw,
                    subject = it.subject,
                    fromRaw = it.fromRaw,
                    fileMtimeEpoch = it.fileMtimeEpoch,
                )
            },
        )
    }

    fun detail(id: String): MessageDetailResponse {
        val message = messageRepository.findById(id) ?: throw ResourceNotFoundException("message not found")
        return MessageDetailResponse(
            id = message.id,
            filePath = message.filePath,
            fileMtimeEpoch = message.fileMtimeEpoch,
            fileSize = message.fileSize,
            dateRaw = message.dateRaw,
            subject = message.subject,
            fromRaw = message.fromRaw,
            messageId = message.messageId,
            textPlain = message.textPlain,
        )
    }
}
