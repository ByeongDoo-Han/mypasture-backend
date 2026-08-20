package com.example.mypasture.config

import com.example.mypasture.auth.infrastructure.JwtAuthenticationFilter
import com.example.mypasture.auth.infrastructure.RestAccessDeniedHandler
import com.example.mypasture.auth.infrastructure.RestAuthenticationEntryPoint
import com.example.mypasture.common.error.RequestCorrelationFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.AndRequestMatcher
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/** Spring Security의 stateless JWT API 보안 정책과 CORS 허용 범위를 설정합니다. */
@Configuration
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** 환경별 허용 출처만 API에 접근할 수 있도록 브라우저 교차 출처 정책을 구성합니다. */
    @Bean
    fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-App-Environment")
            exposedHeaders = listOf(HttpHeaders.LOCATION, RequestCorrelationFilter.TRACE_ID_HEADER)
            // JWT와 refresh token은 Authorization/body로 전달하므로 cross-origin cookie를 허용하지 않습니다.
            allowCredentials = false
            maxAge = 3_600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        redisRequestRateLimitFilter: RedisRequestRateLimitFilter,
        restAuthenticationEntryPoint: RestAuthenticationEntryPoint,
        restAccessDeniedHandler: RestAccessDeniedHandler,
        corsConfigurationSource: CorsConfigurationSource,
    ): SecurityFilterChain =
        http
            .cors { it.configurationSource(corsConfigurationSource) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(restAuthenticationEntryPoint)
                it.accessDeniedHandler(restAccessDeniedHandler)
            }
            .authorizeHttpRequests {
                // 최초 POST에서 JWT를 확인한 AI SSE 요청의 ASYNC 재-dispatch만 허용합니다.
                it.requestMatchers(
                    AndRequestMatcher(
                        DispatcherTypeRequestMatcher(DispatcherType.ASYNC),
                        AntPathRequestMatcher("/api/v1/ai/chat/stream", "POST"),
                    ),
                ).permitAll()
                it.requestMatchers(
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.GET, "/api/v1/auth/health").permitAll()
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/signup",
                    "/api/v1/auth/login",
                    "/api/v1/auth/social-login",
                    "/api/v1/auth/social-login/challenges",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/requests",
                    "/api/v1/auth/password-reset/confirm",
                ).permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/bibles/**", "/api/v1/today-word").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/quiet-time/passages/**", "/api/v1/quiet-time/offline/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/faith-profile/tests/current").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(redisRequestRateLimitFilter, JwtAuthenticationFilter::class.java)
            .build()
}
