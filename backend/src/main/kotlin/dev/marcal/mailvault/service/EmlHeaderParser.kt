package dev.marcal.mailvault.service

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class ParsedHeaders(
    val date: String?,
    val subject: String?,
    val from: String?,
    val messageId: String?,
)

@Component
class EmlHeaderParser {
    fun parse(filePath: Path): ParsedHeaders {
        val headers = linkedMapOf<String, StringBuilder>()
        var currentHeader: String? = null

        Files.newBufferedReader(filePath, StandardCharsets.ISO_8859_1).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isEmpty()) {
                    break
                }

                if ((line.startsWith(" ") || line.startsWith("\t")) && currentHeader != null) {
                    headers[currentHeader]?.append(" ")?.append(line.trim())
                    continue
                }

                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) {
                    currentHeader = null
                    continue
                }

                val headerName = line.substring(0, separatorIndex).trim().lowercase()
                val headerValue = line.substring(separatorIndex + 1).trim()
                headers[headerName] = StringBuilder(headerValue)
                currentHeader = headerName
            }
        }

        return ParsedHeaders(
            date = headers["date"]?.toString(),
            subject = headers["subject"]?.toString(),
            from = headers["from"]?.toString(),
            messageId = headers["message-id"]?.toString(),
        )
    }
}
