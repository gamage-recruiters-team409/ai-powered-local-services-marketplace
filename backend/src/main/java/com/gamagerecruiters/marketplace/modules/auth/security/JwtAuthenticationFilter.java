package com.gamagerecruiters.marketplace.modules.auth.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gamagerecruiters.marketplace.common.dto.ErrorResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> ALLOWED_PASSWORD_CHANGE_PATHS = Set.of(
            "/api/v1/auth/change-password",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh"
    );

    private final TokenService tokenService;
    private final UserInternalService userInternalService;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            TokenService tokenService,
            UserInternalService userInternalService,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.userInternalService = userInternalService;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            if (!tokenService.validateToken(token)) {
                // If an invalid/expired token was explicitly supplied, reject immediately
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException("Authentication token is invalid or expired")
                );
                return;
            }

            try {
                String userId = tokenService.getUserIdFromToken(token);
                Role tokenRole = tokenService.getRoleFromToken(token);

                // Issue 2: Load the CURRENT user from DB via UserInternalService
                User user;
                try {
                    user = userInternalService.getById(userId);
                } catch (Exception e) {
                    user = null;
                }

                // Reject if user does not exist or status is not ACTIVE
                if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                    jwtAuthenticationEntryPoint.commence(
                            request,
                            response,
                            new BadCredentialsException("User account is inactive or does not exist")
                    );
                    return;
                }

                // Reject if token role differs from current database role
                if (user.getRole() != tokenRole) {
                    jwtAuthenticationEntryPoint.commence(
                            request,
                            response,
                            new BadCredentialsException("Token permissions have been superseded by account changes")
                    );
                    return;
                }

                // Reject if user must change password and request is not on the allow-list
                if (user.isMustChangePassword() && !isAllowedDuringPasswordChange(request)) {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    ErrorResponse errorResponse = new ErrorResponse(
                            HttpStatus.FORBIDDEN.value(),
                            "PASSWORD_CHANGE_REQUIRED",
                            "Password change required before accessing this resource",
                            request.getRequestURI()
                    );
                    objectMapper.writeValue(response.getOutputStream(), errorResponse);
                    return;
                }

                // Build principal using current database role and state
                UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), user.getRole());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException("Failed to authenticate token")
                );
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private boolean isAllowedDuringPasswordChange(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (StringUtils.hasText(contextPath) && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return ALLOWED_PASSWORD_CHANGE_PATHS.contains(path);
    }
}
