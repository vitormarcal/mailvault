package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.CleanupResponse
import dev.marcal.mailvault.api.VacuumResponse
import dev.marcal.mailvault.service.MaintenanceService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/maintenance")
class MaintenanceController(
    private val maintenanceService: MaintenanceService,
) {
    @PostMapping("/cleanup")
    fun cleanup(): CleanupResponse = maintenanceService.cleanup()

    @PostMapping("/vacuum")
    fun vacuum(): VacuumResponse = maintenanceService.vacuum()
}
