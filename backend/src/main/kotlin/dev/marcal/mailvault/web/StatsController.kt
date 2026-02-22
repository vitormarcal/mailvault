package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.StatsResponse
import dev.marcal.mailvault.repository.StatsRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class StatsController(
    private val statsRepository: StatsRepository,
) {
    @GetMapping("/stats")
    fun stats(): StatsResponse = statsRepository.getStats()
}
