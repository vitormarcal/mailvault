package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.UiFreezeOnIndexRequest
import dev.marcal.mailvault.api.UiFreezeOnIndexResponse
import dev.marcal.mailvault.service.UiFreezeOnIndexService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ui")
class UiFreezeOnIndexController(
    private val uiFreezeOnIndexService: UiFreezeOnIndexService,
) {
    @GetMapping("/freeze-on-index")
    fun getFreezeOnIndex(): UiFreezeOnIndexResponse = UiFreezeOnIndexResponse(enabled = uiFreezeOnIndexService.isEnabled())

    @PutMapping("/freeze-on-index")
    fun setFreezeOnIndex(
        @RequestBody request: UiFreezeOnIndexRequest,
    ): UiFreezeOnIndexResponse = UiFreezeOnIndexResponse(enabled = uiFreezeOnIndexService.setEnabled(request.enabled))
}
