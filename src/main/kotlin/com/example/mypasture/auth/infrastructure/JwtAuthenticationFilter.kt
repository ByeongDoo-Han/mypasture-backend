package com.example.mypasture.auth.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** JWT 서명과 현재 활성 계정의 인증 버전을 확인해 SecurityContext에 인증 정보를 주입합니다. */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader(AUTHORIZATION_HEADER)?.extractBearerToken()
        if (token != null) {
            try {
                val principal = jwtTokenProvider.parseAccessToken(token)
                val activeUser = userRepository.findByIdAndDeletedAtIsNull(principal.userId)
                if (activeUser == null || activeUser.authVersion != principal.authVersion) {
                    throw InvalidJwtTokenException()
                }

                val authentication =
                    UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}")),
                    )
                SecurityContextHolder.getContext().authentication = authentication
            } catch (ex: JwtAuthenticationException) {
                SecurityContextHolder.clearContext()
                request.setAttribute(JWT_ERROR_ATTRIBUTE, ex.message)
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun String.extractBearerToken(): String? =
        takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    companion object {
        const val JWT_ERROR_ATTRIBUTE = "jwtError"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
