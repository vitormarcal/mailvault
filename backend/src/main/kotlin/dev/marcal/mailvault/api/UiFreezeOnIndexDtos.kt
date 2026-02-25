package dev.marcal.mailvault.api

data class UiFreezeOnIndexResponse(
    val enabled: Boolean,
)

data class UiFreezeOnIndexRequest(
    val enabled: Boolean? = null,
)
