package com.dumio.backend.security;

import com.dumio.backend.dto.response.ErrorResponse;
import com.dumio.backend.enums.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper json;
    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

    public RestAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        ErrorCode code = ErrorCode.INVALID_ACCESS_TOKEN;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException || cause instanceof TransactionException) {
                code = ErrorCode.SERVICE_UNAVAILABLE;
                break;
            }
            if (cause instanceof JwtValidationException validation && validation.getErrors().stream()
                    .anyMatch(error -> ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(error.getErrorCode()))) {
                code = ErrorCode.SERVICE_UNAVAILABLE;
                break;
            }
        }
        if (code == ErrorCode.INVALID_ACCESS_TOKEN) {
            // Preserve the Bearer challenge without exposing parser/SQL details.
            bearer.commence(request, response,
                    new InvalidBearerTokenException("Access token is invalid or expired"));
        }
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ErrorResponse.from(code));
    }
}
