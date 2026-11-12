package com.nereonix.nereonet_mvp.navigation;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The sidebar toggle without JavaScript (P29): the "Expand menu" / "Collapse menu" button is a small form that posts
 * here; the answer sets the cookie SidebarPreference reads and goes back to the same page. With JavaScript,
 * js/sidebar.js sets the same cookie and the form is never sent. A display preference of this browser, not a change
 * to any record, so it is not in the audit log and shows no message.
 */
@Controller
public class SidebarPreferenceController {

    @PostMapping("/preferences/sidebar")
    public String save(@RequestParam(required = false) String state, @RequestParam(required = false) String returnTo,
                       HttpServletResponse response) {
        String value = SidebarPreference.EXPANDED.equals(state) ? SidebarPreference.EXPANDED : SidebarPreference.COLLAPSED;
        ResponseCookie cookie = ResponseCookie.from(SidebarPreference.COOKIE, value)
                .path("/")
                .maxAge(SidebarPreference.MAX_AGE)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:" + SidebarPreference.safeReturnTo(returnTo);
    }
}
