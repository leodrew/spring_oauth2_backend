package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17 interim guard: with app.security.required-role set, "any authenticated
 * realm user" is no longer enough — the Keycloak realm role (mapped to
 * ROLE_<name>) is required for everything beyond the permitAll list.
 */
@SpringBootTest(properties = "app.security.required-role=epmm-user")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequiredRoleAccessTest {

    @Autowired MockMvc mockMvc;

    @Test
    void authenticatedUserWithoutTheRoleIsDenied() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/data")
                        .with(oauth2Login().authorities(new SimpleGrantedAuthority("OIDC_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithTheRolePassesAuthorization() throws Exception {
        // no controller behind this path — security-pass shows up as 404
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/data")
                        .with(oauth2Login().authorities(
                                new SimpleGrantedAuthority("OIDC_USER"),
                                new SimpleGrantedAuthority("ROLE_epmm-user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void permitAllPathsStayPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
