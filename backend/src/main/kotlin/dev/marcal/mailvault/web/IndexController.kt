package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.IndexJobStartResponse
import dev.marcal.mailvault.api.IndexJobStatusResponse
import dev.marcal.mailvault.service.IndexJobService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class IndexController(
    private val indexJobService: IndexJobService,
) {
    @PostMapping("/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun startIndex(): IndexJobStartResponse {
        val snapshot = indexJobService.startIncremental()
        return IndexJobStartResponse(
            jobId = snapshot.jobId,
            status = snapshot.status.name,
            startedAt = snapshot.startedAt.toString(),
            alreadyRunning = snapshot.alreadyRunning,
        )
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun startReindex(): IndexJobStartResponse {
        val snapshot = indexJobService.startFull()
        return IndexJobStartResponse(
            jobId = snapshot.jobId,
            status = snapshot.status.name,
            startedAt = snapshot.startedAt.toString(),
            alreadyRunning = snapshot.alreadyRunning,
        )
    }

    @GetMapping("/index/jobs/{jobId}")
    fun getIndexJob(
        @PathVariable jobId: String,
    ): IndexJobStatusResponse {
        val snapshot = indexJobService.get(jobId)
        val progressPercent =
            if (snapshot.totalFiles != null && snapshot.totalFiles > 0 && snapshot.processedFiles != null) {
                ((snapshot.processedFiles.toDouble() / snapshot.totalFiles.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            } else {
                null
            }
        return IndexJobStatusResponse(
            jobId = snapshot.jobId,
            status = snapshot.status.name,
            phase = snapshot.phase.name,
            startedAt = snapshot.startedAt.toString(),
            finishedAt = snapshot.finishedAt?.toString(),
            totalFiles = snapshot.totalFiles,
            processedFiles = snapshot.processedFiles,
            progressPercent = progressPercent,
            freezeTotal = snapshot.freezeTotal,
            freezeCompleted = snapshot.freezeCompleted,
            result = snapshot.result,
            error = snapshot.error,
        )
    }
}
