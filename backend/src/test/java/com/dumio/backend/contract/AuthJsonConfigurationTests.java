package com.dumio.backend.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dumio.backend.dto.request.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.otp.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.mail.username=test@example.com", "spring.mail.password=unused"})
class AuthJsonConfigurationTests {

    @Autowired
    private JsonMapper json;

    @Autowired
    private ApplicationContext context;

    @Test
    void bootUsesStrictJsonConfigurationWithoutRegisteringContractFixtureEndpoints() {
        assertThrows(JacksonException.class, () -> json.readValue("""
                {"username":"An","email":"an@example.com","password":"Abcde1!x",
                 "confirmPassword":"Abcde1!x","role":"ADMIN"}
                """, RegisterRequest.class));
        assertThrows(JacksonException.class, () -> json.readValue("""
                {"username":"An","email":"an@example.com","password":12345678,
                 "confirmPassword":"Abcde1!x"}
                """, RegisterRequest.class));
        assertEquals(0, context.getBeanNamesForType(AuthContractTests.ContractFixture.class).length);
        assertEquals(0, context.getBeanNamesForAnnotation(
                org.springframework.boot.test.context.TestComponent.class).length);
    }
}
