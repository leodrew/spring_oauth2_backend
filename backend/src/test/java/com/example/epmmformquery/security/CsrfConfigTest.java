package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void csrfTokenIsExposedAsReadableCookieForTheSpa() throws Exception {
        mockMvc.perform(get("/gui_epmmFormQuery/rs/gui/ping"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void apiPostWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/gui_epmmFormQuery/rs/anything").with(oauth2Login()))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiPostWithCsrfTokenPassesSecurity() throws Exception {
        // No controller behind this path: passing security means 404, not 403
        mockMvc.perform(post("/gui_epmmFormQuery/rs/anything").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
