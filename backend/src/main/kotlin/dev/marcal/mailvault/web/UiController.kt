package dev.marcal.mailvault.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class UiController {
    @GetMapping("/")
    fun home(): String = "forward:/index.html"

    @GetMapping("/messages/{id}")
    fun message(@PathVariable id: String): String = "forward:/message.html"
}
