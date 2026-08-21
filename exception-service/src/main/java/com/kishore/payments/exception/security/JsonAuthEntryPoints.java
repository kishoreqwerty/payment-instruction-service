package com.kishore.payments.exception.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Renders the same {@code {"error", "detail"}} shape {@code
 * ApiExceptionHandler} uses for every other 4xx this API returns. Without
 * this, an authentication failure (no/bad credentials) or an authorisation
 * failure (wrong role on a {@code @PreAuthorize}-guarded endpoint) is
 * handled entirely inside the security filter chain, before a request ever
 * reaches a controller -- {@code @RestControllerAdvice} cannot intercept it
 * -- so Spring Boot's default error handling takes over instead and returns
 * a differently-shaped body (or, for a plain 403 with no {@code Accept}
 * negotiation, none at all). Building the ops-dashboard against this API
 * exposed exactly that inconsistency: acceptance criterion 5 requires a 403
 * to "render as a clear message, not a blank screen or a raw error," which
 * a client cannot do reliably against two different error shapes for what
 * is, from its point of view, the same kind of failure.
 */
class JsonAuthEntryPoints implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    JsonAuthEntryPoints(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required to access this resource");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Your role does not permit this action");
    }

    private void write(HttpServletResponse response, int status, String error, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorBody(error, detail));
    }

    record ErrorBody(String error, String detail) {
    }
}
