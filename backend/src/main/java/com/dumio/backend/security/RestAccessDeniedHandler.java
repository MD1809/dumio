package com.dumio.backend.security;

import com.dumio.backend.dto.response.ErrorResponse;
import com.dumio.backend.enums.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;
    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();

    public RestAccessDeniedHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        ErrorCode code = exception instanceof CsrfException
                ? ErrorCode.CSRF_INVALID : ErrorCode.ACCESS_DENIED;
        if (code == ErrorCode.ACCESS_DENIED) {
            bearer.handle(request, response, exception);
        }
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ErrorResponse.from(code));
    }
}
