package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.AttachmentResponse
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.domain.AttachmentRecord
import dev.marcal.mailvault.repository.AttachmentRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class AttachmentService(
    private val attachmentRepository: AttachmentRepository,
    private val mailVaultProperties: MailVaultProperties,
) {
    fun listByMessage(messageId: String): List<AttachmentResponse> =
        attachmentRepository.listByMessageId(messageId).map { it.toResponse() }

    fun resolveInlineCid(messageId: String, cid: String): AttachmentFile {
        val attachment = attachmentRepository.findInlineByMessageAndCid(messageId, cid)
            ?: throw ResourceNotFoundException("inline attachment not found")
        return loadAttachmentFile(attachment)
    }

    fun resolveDownload(attachmentId: String): AttachmentFile {
        val attachment = attachmentRepository.findById(attachmentId)
            ?: throw ResourceNotFoundException("attachment not found")
        return loadAttachmentFile(attachment)
    }

    private fun loadAttachmentFile(attachment: AttachmentRecord): AttachmentFile {
        val filePath = Path.of(attachment.storagePath).toAbsolutePath().normalize()
        val attachmentsBaseDir = Path.of(mailVaultProperties.storageDir).toAbsolutePath().normalize().resolve("attachments")
        if (!filePath.startsWith(attachmentsBaseDir)) {
            throw ResourceNotFoundException("attachment file not found")
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw ResourceNotFoundException("attachment file not found")
        }

        return AttachmentFile(
            id = attachment.id,
            messageId = attachment.messageId,
            filename = attachment.filename,
            contentType = attachment.contentType ?: "application/octet-stream",
            size = attachment.size,
            inlineCid = attachment.inlineCid,
            bytes = Files.readAllBytes(filePath),
        )
    }

    private fun AttachmentRecord.toResponse(): AttachmentResponse =
        AttachmentResponse(
            id = id,
            filename = filename,
            contentType = contentType,
            size = size,
            isInline = !inlineCid.isNullOrBlank(),
            inlineCid = inlineCid,
        )
}

data class AttachmentFile(
    val id: String,
    val messageId: String,
    val filename: String?,
    val contentType: String,
    val size: Long,
    val inlineCid: String?,
    val bytes: ByteArray,
)
