package dev.marcal.mailvault.controller

import dev.marcal.mailvault.service.IndexResult
import dev.marcal.mailvault.service.IndexerService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class IndexController(
    private val indexerService: IndexerService,
) {
    @PostMapping("/index")
    fun index(): IndexResult =
        try {
            indexerService.index()
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
}
