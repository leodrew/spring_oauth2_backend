package com.example.epmmformquery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecuritySmokeTest {

    @Autowired MockMvc mockMvc;

    @Test
    void protectedPageRedirectsToKeycloakAuthorization() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/web/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        containsString("/gui_epmmFormQuery/oauth2/authorization/keycloak")));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void pingIsPublic() throws Exception {
        // permitAll'd in SecurityConfig; no controller exists so security-pass = 404
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/ping"))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorInfoIsNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void logoutEndpointIsRoutedViaGet() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/logout").with(oauth2Login()))
                .andExpect(status().is3xxRedirection());
    }
}
