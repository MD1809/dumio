package com.dumio.backend.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dumio.backend.config.JwtConfig;
import com.dumio.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
@AutoConfigureMockMvc
@Import(AuthSecurityTests.Fixture.class)
class AuthSecurityFailureTests {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean UserRepository users;

    @Test
    void storageFailureReturns503WithoutExposingInternalDetails() throws Exception {
        when(users.findById(any())).thenThrow(
                new DataAccessResourceFailureException("private-database-connection-detail"));
        var result = mvc.perform(get("/__security/private")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(false)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("fieldErrors").isEmpty())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("private-database"));
    }

    @Test
    void expiredJwtIsRejectedBeforeLookingUpAccount() throws Exception {
        mvc.perform(get("/__security/private").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(true)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
        verify(users, never()).findById(any());
    }

    private String token(boolean expired) {
        Instant issued = Instant.now().minusSeconds(expired ? 1200 : 0);
        var claims = JwtClaimsSet.builder().subject(UUID.randomUUID().toString())
                .issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
                .issuedAt(issued).notBefore(issued).expiresAt(issued.plusSeconds(JwtConfig.ACCESS_TOKEN_SECONDS))
                .claim("roles", List.of("USER")).claim("token_version", 0L).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
