package com.nereonix.nereonet_mvp.error;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.RequestDispatcher;

/**
 * Spring Boot's error attributes plus a reference for the error pages (P12): templates/error/5xx.html shows
 * "11 Nov 2026 · 14:03 UTC · 7KQ3MX2P" for the user to quote to the office, and the same reference is logged with
 * the failed request, next to the stack trace the server logs.
 *   errorTime       when it happened (Instant, shown in UTC with layout/parts :: localTime)
 *   errorReference  eight characters without 0/O or 1/I, easy to read out over a phone or radio
 */
@Component
public class ErrorReferenceAttributes extends DefaultErrorAttributes {

    static final String TIME = "errorTime";
    static final String REFERENCE = "errorReference";

    private static final Logger log = LoggerFactory.getLogger(ErrorReferenceAttributes.class);
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final Clock clock = Clock.systemUTC();

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        // Once per request, also when the attributes are asked for twice.
        String reference = (String) webRequest.getAttribute(REFERENCE, RequestAttributes.SCOPE_REQUEST);
        Instant time = (Instant) webRequest.getAttribute(TIME, RequestAttributes.SCOPE_REQUEST);
        if (reference == null) {
            reference = newReference();
            time = clock.instant();
            webRequest.setAttribute(REFERENCE, reference, RequestAttributes.SCOPE_REQUEST);
            webRequest.setAttribute(TIME, time, RequestAttributes.SCOPE_REQUEST);
            logServerError(webRequest, attributes.get("status"), reference);
        }
        attributes.put(REFERENCE, reference);
        attributes.put(TIME, time);
        return attributes;
    }

    private void logServerError(WebRequest webRequest, Object status, String reference) {
        if (!(status instanceof Integer code) || code < 500) {
            return;
        }
        Throwable error = getError(webRequest);
        Object uri = webRequest.getAttribute(RequestDispatcher.ERROR_REQUEST_URI, RequestAttributes.SCOPE_REQUEST);
        log.error("Error reference {}: status {} on {}: {}", reference, code, uri,
                error == null ? "no exception" : error.toString());
    }

    static String newReference() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder reference = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }
}
