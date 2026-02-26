package dev.marcal.mailvault.config

import dev.marcal.mailvault.service.AuthBootstrapService
import dev.marcal.mailvault.service.LoginAttemptService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.DefaultRedirectStrategy
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
        loginAttemptService: LoginAttemptService,
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
                    .permitAll()
                    .successHandler { request, response, _ ->
                        loginAttemptService.onAuthenticationSuccess()
                        DefaultRedirectStrategy().sendRedirect(request, response, "/")
                    }.failureHandler { request, response, exception ->
                        if (!isLockoutFailure(exception)) {
                            loginAttemptService.onAuthenticationFailure()
                        }
                        DefaultRedirectStrategy().sendRedirect(request, response, "/login?error")
                    }
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
    fun userDetailsService(
        authBootstrapService: AuthBootstrapService,
        loginAttemptService: LoginAttemptService,
    ): UserDetailsService =
        UserDetailsService { username ->
            loginAttemptService.ensureLoginAllowed()
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

    private fun isLockoutFailure(exception: AuthenticationException): Boolean =
        exception is org.springframework.security.authentication.LockedException
}
