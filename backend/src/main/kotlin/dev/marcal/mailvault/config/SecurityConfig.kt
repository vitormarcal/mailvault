package dev.marcal.mailvault.config

import dev.marcal.mailvault.service.AuthBootstrapService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        setupBootstrapRequestMatcher: RequestMatcher,
    ): SecurityFilterChain =
        http
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            }.authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.GET, "/api/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/login", "/login.html")
                    .permitAll()
                    .requestMatchers(setupBootstrapRequestMatcher)
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.formLogin {
                it
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true)
                    .permitAll()
            }.headers {
                it.contentTypeOptions(Customizer.withDefaults())
                it.frameOptions { options -> options.deny() }
            }.addFilterAfter(CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter::class.java)
            .build()

    @Bean
    fun setupBootstrapRequestMatcher(authBootstrapService: AuthBootstrapService): RequestMatcher =
        SetupBootstrapRequestMatcher(authBootstrapService)

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(authBootstrapService: AuthBootstrapService): UserDetailsService =
        UserDetailsService { username ->
            val credentials =
                authBootstrapService.credentials()
                    ?: throw UsernameNotFoundException("No credentials configured yet")
            if (credentials.username != username) {
                throw UsernameNotFoundException("Invalid username")
            }

            User
                .withUsername(credentials.username)
                .password(credentials.passwordHash)
                .roles("USER")
                .build()
        }
}
