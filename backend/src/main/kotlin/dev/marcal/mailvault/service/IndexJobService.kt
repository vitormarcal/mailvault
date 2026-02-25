package dev.marcal.mailvault.service

import dev.marcal.mailvault.util.ResourceNotFoundException
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors

enum class IndexJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class IndexJobSnapshot(
    val jobId: String,
    val status: IndexJobStatus,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val result: IndexResult?,
    val error: String?,
    val alreadyRunning: Boolean,
    val totalFiles: Int?,
    val processedFiles: Int?,
)

@Service
class IndexJobService(
    private val indexerService: IndexerService,
) {
    private val logger = LoggerFactory.getLogger(IndexJobService::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val jobs = linkedMapOf<String, InternalJob>()
    private var runningJobId: String? = null

    fun start(): IndexJobSnapshot {
        synchronized(lock) {
            val currentJobId = runningJobId
            if (currentJobId != null) {
                val existing = jobs[currentJobId] ?: throw ResourceNotFoundException("Index job not found: $currentJobId")
                return existing.toSnapshot(alreadyRunning = true)
            }

            val now = OffsetDateTime.now()
            val jobId = UUID.randomUUID().toString()
            val job =
                InternalJob(
                    jobId = jobId,
                    status = IndexJobStatus.RUNNING,
                    startedAt = now,
                    finishedAt = null,
                    result = null,
                    error = null,
                    totalFiles = null,
                    processedFiles = null,
                )
            jobs[jobId] = job
            runningJobId = jobId
            logger.info("Index job accepted jobId={}", jobId)
            executor.submit { runJob(jobId) }
            return job.toSnapshot(alreadyRunning = false)
        }
    }

    fun get(jobId: String): IndexJobSnapshot {
        synchronized(lock) {
            val job = jobs[jobId] ?: throw ResourceNotFoundException("Index job not found: $jobId")
            return job.toSnapshot(alreadyRunning = runningJobId == jobId)
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdown()
    }

    private fun runJob(jobId: String) {
        try {
            val result =
                indexerService.index { progress ->
                    synchronized(lock) {
                        val job = jobs[jobId] ?: return@synchronized
                        job.totalFiles = progress.totalFiles
                        job.processedFiles = progress.processedFiles
                    }
                }
            synchronized(lock) {
                val job = jobs[jobId] ?: return
                job.status = IndexJobStatus.SUCCEEDED
                job.result = result
                job.finishedAt = OffsetDateTime.now()
                job.error = null
                if (runningJobId == jobId) {
                    runningJobId = null
                }
                pruneCompletedJobs()
                logger.info(
                    "Index job succeeded jobId={} inserted={} updated={} skipped={}",
                    jobId,
                    result.inserted,
                    result.updated,
                    result.skipped,
                )
            }
        } catch (e: Exception) {
            synchronized(lock) {
                val job = jobs[jobId] ?: return
                job.status = IndexJobStatus.FAILED
                job.result = null
                job.finishedAt = OffsetDateTime.now()
                job.error = e.message ?: "Unexpected error"
                if (runningJobId == jobId) {
                    runningJobId = null
                }
                pruneCompletedJobs()
                logger.error("Index job failed jobId={} reason={}", jobId, job.error, e)
            }
        }
    }

    private fun pruneCompletedJobs() {
        val completedIds =
            jobs
                .entries
                .asSequence()
                .filter { it.value.status != IndexJobStatus.RUNNING }
                .map { it.key }
                .toList()
        if (completedIds.size <= MAX_COMPLETED_JOBS) {
            return
        }
        val toRemove = completedIds.take(completedIds.size - MAX_COMPLETED_JOBS)
        toRemove.forEach { jobs.remove(it) }
    }

    private data class InternalJob(
        val jobId: String,
        var status: IndexJobStatus,
        val startedAt: OffsetDateTime,
        var finishedAt: OffsetDateTime?,
        var result: IndexResult?,
        var error: String?,
        var totalFiles: Int?,
        var processedFiles: Int?,
    ) {
        fun toSnapshot(alreadyRunning: Boolean): IndexJobSnapshot =
            IndexJobSnapshot(
                jobId = jobId,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                result = result,
                error = error,
                alreadyRunning = alreadyRunning,
                totalFiles = totalFiles,
                processedFiles = processedFiles,
            )
    }

    private companion object {
        const val MAX_COMPLETED_JOBS = 25
    }
}
