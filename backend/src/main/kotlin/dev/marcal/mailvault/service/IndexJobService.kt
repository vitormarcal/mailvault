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
    val phase: IndexProgressPhase,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val result: IndexResult?,
    val error: String?,
    val alreadyRunning: Boolean,
    val totalFiles: Int?,
    val processedFiles: Int?,
    val freezeTotal: Int?,
    val freezeCompleted: Int?,
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

    fun startIncremental(): IndexJobSnapshot = start(IndexMode.INCREMENTAL)

    fun startFull(): IndexJobSnapshot = start(IndexMode.FULL)

    private fun start(mode: IndexMode): IndexJobSnapshot {
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
                    phase = IndexProgressPhase.INDEXING,
                    mode = mode,
                    startedAt = now,
                    finishedAt = null,
                    result = null,
                    error = null,
                    totalFiles = null,
                    processedFiles = null,
                    freezeTotal = null,
                    freezeCompleted = null,
                )
            jobs[jobId] = job
            runningJobId = jobId
            logger.info("Index job accepted jobId={} mode={}", jobId, mode)
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
            val mode = synchronized(lock) { jobs[jobId]?.mode } ?: IndexMode.INCREMENTAL
            val result =
                indexerService.index(mode) { progress ->
                    synchronized(lock) {
                        val job = jobs[jobId] ?: return@synchronized
                        job.phase = progress.phase
                        job.totalFiles = progress.totalFiles
                        job.processedFiles = progress.processedFiles
                        job.freezeTotal = progress.freezeTotal
                        job.freezeCompleted = progress.freezeCompleted
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
                    "Index job succeeded jobId={} mode={} inserted={} updated={} skipped={}",
                    jobId,
                    job.mode,
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
        var phase: IndexProgressPhase,
        val mode: IndexMode,
        val startedAt: OffsetDateTime,
        var finishedAt: OffsetDateTime?,
        var result: IndexResult?,
        var error: String?,
        var totalFiles: Int?,
        var processedFiles: Int?,
        var freezeTotal: Int?,
        var freezeCompleted: Int?,
    ) {
        fun toSnapshot(alreadyRunning: Boolean): IndexJobSnapshot =
            IndexJobSnapshot(
                jobId = jobId,
                status = status,
                phase = phase,
                startedAt = startedAt,
                finishedAt = finishedAt,
                result = result,
                error = error,
                alreadyRunning = alreadyRunning,
                totalFiles = totalFiles,
                processedFiles = processedFiles,
                freezeTotal = freezeTotal,
                freezeCompleted = freezeCompleted,
            )
    }

    private companion object {
        const val MAX_COMPLETED_JOBS = 25
    }
}
