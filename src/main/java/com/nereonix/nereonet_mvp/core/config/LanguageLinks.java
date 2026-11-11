package com.nereonix.nereonet_mvp.core.config;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UrlPathHelper;

/**
 * The links of the English / Greek switch (layout/parts :: langSwitch, P16), used as {@code @languageLinks} in
 * Thymeleaf. A link changes only the language: it is the current page's query string with {@code lang} set, as a
 * relative URL ("?lang=el&amp;shipId=1&amp;status=expired"), so the browser keeps the path and the filtered list,
 * page number or invitation token stay as they are. {@code lang} comes first; the other parameters keep their order
 * and their encoding.
 * <p>
 * Only the query string of the URL is used, never the body of a POST, so a form posted with a password never ends
 * up in a link. On an error page the query string is the one of the request that failed.
 */
@Component("languageLinks")
public class LanguageLinks {

    /** The parameter LocaleConfig's LocaleChangeInterceptor reads. */
    static final String PARAM = "lang";
    private static final String TOKEN = "token";

    /** The current page in {@code language}. */
    public String to(String language) {
        return to(language, null);
    }

    /**
     * The current page in {@code language}. token: the invitation or reset token of the page, added when the URL
     * does not carry it (the registration form shown again after a POST with errors); null everywhere else.
     */
    public String to(String language, String token) {
        StringBuilder link = new StringBuilder("?").append(PARAM).append('=').append(encode(language));
        boolean hasToken = false;
        String query = currentQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String name = decode(pair.split("=", 2)[0]);
                if (name.equals(PARAM)) {
                    continue;
                }
                hasToken |= name.equals(TOKEN);
                link.append('&').append(pair);
            }
        }
        if (token != null && !hasToken) {
            link.append('&').append(TOKEN).append('=').append(encode(token));
        }
        return link.toString();
    }

    private static String currentQuery() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return UrlPathHelper.defaultInstance.getOriginatingQueryString(servlet.getRequest());
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A parameter name as the server reads it; a malformed escape is compared as written. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
