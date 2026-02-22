package dev.marcal.mailvault.config

import dev.marcal.mailvault.api.ErrorResponse
import dev.marcal.mailvault.util.ResourceNotFoundException
import dev.marcal.mailvault.util.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.time.OffsetDateTime

@ControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("VALIDATION_ERROR", ex.message ?: "Validation failed"))

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(ex: ResourceNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", ex.message ?: "Resource not found"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("BAD_REQUEST", ex.message ?: "Invalid request"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected error", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(error("INTERNAL_ERROR", "Unexpected server error"))
    }

    private fun error(code: String, message: String): ErrorResponse =
        ErrorResponse(
            error = code,
            message = message,
            timestamp = OffsetDateTime.now().toString(),
        )
}
