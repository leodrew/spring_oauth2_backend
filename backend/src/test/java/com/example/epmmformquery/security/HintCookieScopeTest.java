package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class HintCookieScopeTest {

    @Test
    void loginSuccessHandlerScopesHintCookieToContextPrefix() throws Exception {
        LoginSuccessHandler handler = new LoginSuccessHandler(
                new HttpSessionRequestCache(), "/gui_epmmFormQuery/web/",
                null, "ea_login_hint", 2592000, "/gui_epmmFormQuery", true);

        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res,
                new TestingAuthenticationToken("leo", "n/a"));

        Cookie c = res.getCookie("ea_login_hint");
        assertThat(c).isNotNull();
        assertThat(c.getPath()).isEqualTo("/gui_epmmFormQuery");
        assertThat(c.getSecure()).isTrue();
        assertThat(c.isHttpOnly()).isTrue();
        // explicit SameSite (review F6) — the servlet session.cookie property
        // only covers the session cookie, not manually built ones
        assertThat(c.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
