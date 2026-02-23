package dev.marcal.mailvault.service

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Service
class AttachmentStorageService {
    fun resetMessageDirectory(messageDir: Path) {
        if (Files.exists(messageDir)) {
            Files
                .walk(messageDir)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
        Files.createDirectories(messageDir)
    }

    fun writeAttachment(
        messageDir: Path,
        fileName: String,
        bytes: ByteArray,
    ): Path {
        val target = messageDir.resolve(fileName)
        Files.write(target, bytes)
        return target
    }
}
