package dev.marcal.mailvault.util

class ValidationException(
    message: String,
) : RuntimeException(message)

class ResourceNotFoundException(
    message: String,
) : RuntimeException(message)
