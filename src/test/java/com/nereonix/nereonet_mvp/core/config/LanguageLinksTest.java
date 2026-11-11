package com.nereonix.nereonet_mvp.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.WebUtils;

/** P16: the links of the language switch, for the cases a MockMvc page does not reach. */
class LanguageLinksTest {

    private final LanguageLinks links = new LanguageLinks();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theTokenOfAFormShownAgainIsAddedOnceAndOnlyWhenTheUrlLacksIt() {
        // POST /register with errors: the token is in the form, not in the URL.
        request("/register", null);
        assertThat(links.to("el", "abc.def")).isEqualTo("?lang=el&token=abc.def");
        // GET /register?token=...: already in the URL.
        request("/register", "token=abc.def&lang=en");
        assertThat(links.to("el", "abc.def")).isEqualTo("?lang=el&token=abc.def");
    }

    @Test
    void anErrorPageKeepsTheQueryOfTheRequestThatFailed() {
        MockHttpServletRequest request = request("/error", null);
        request.setAttribute(WebUtils.FORWARD_REQUEST_URI_ATTRIBUTE, "/office/crew");
        request.setAttribute(WebUtils.FORWARD_QUERY_STRING_ATTRIBUTE, "shipId=7");
        assertThat(links.to("el")).isEqualTo("?lang=el&shipId=7");
    }

    @Test
    void anEncodedLangParameterIsReplacedToo() {
        request("/office/crew", "%6Cang=el&shipId=7&&");
        assertThat(links.to("en")).isEqualTo("?lang=en&shipId=7");
    }

    @Test
    void outsideARequestOnlyTheLanguage() {
        assertThat(links.to("el")).isEqualTo("?lang=el");
    }

    private MockHttpServletRequest request(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(query);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
