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
        val snapshot = indexJobService.start()
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
        return IndexJobStatusResponse(
            jobId = snapshot.jobId,
            status = snapshot.status.name,
            startedAt = snapshot.startedAt.toString(),
            finishedAt = snapshot.finishedAt?.toString(),
            result = snapshot.result,
            error = snapshot.error,
        )
    }
}
