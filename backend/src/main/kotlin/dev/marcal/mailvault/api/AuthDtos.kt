package dev.marcal.mailvault.api

data class ChangePasswordRequest(
    val currentPassword: String? = null,
    val newPassword: String? = null,
)

data class ChangePasswordResponse(
    val ok: Boolean,
)
