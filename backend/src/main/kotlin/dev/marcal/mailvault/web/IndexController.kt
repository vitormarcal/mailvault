package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.IndexResult
import dev.marcal.mailvault.service.IndexerService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class IndexController(
    private val indexerService: IndexerService,
) {
    @PostMapping("/index")
    fun index(): IndexResult = indexerService.index()
}
