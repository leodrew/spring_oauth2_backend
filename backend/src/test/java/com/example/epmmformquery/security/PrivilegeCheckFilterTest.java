package com.example.epmmformquery.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17: the privilege toggle must FAIL CLOSED while the LDAP logic is a stub —
 * enabling it must never silently allow everything through.
 */
class PrivilegeCheckFilterTest {

    private PrivilegeCheckFilter filter(boolean enabled) {
        PrivilegeCheckFilter filter = new PrivilegeCheckFilter();
        ReflectionTestUtils.setField(filter, "enabled", enabled);
        return filter;
    }

    @Test
    void disabledPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(false).doFilter(new MockHttpServletRequest("GET", "/gui_epmmFormQuery/rs/x"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void enabledButUnimplementedDeniesWith503() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(new MockHttpServletRequest("GET", "/gui_epmmFormQuery/rs/x"),
                response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }
}
