package dev.marcal.mailvault.controller

import dev.marcal.mailvault.service.IndexResult
import dev.marcal.mailvault.service.IndexerService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class IndexRequest(
    val rootDir: String,
)

@RestController
@RequestMapping("/api")
class IndexController(
    private val indexerService: IndexerService,
) {
    @PostMapping("/index")
    fun index(@RequestBody request: IndexRequest): IndexResult =
        try {
            indexerService.index(request.rootDir)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
}
