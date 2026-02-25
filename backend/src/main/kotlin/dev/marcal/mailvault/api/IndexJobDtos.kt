package dev.marcal.mailvault.api

import dev.marcal.mailvault.service.IndexResult

data class IndexJobStartResponse(
    val jobId: String,
    val status: String,
    val startedAt: String,
    val alreadyRunning: Boolean,
)

data class IndexJobStatusResponse(
    val jobId: String,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val result: IndexResult?,
    val error: String?,
)
