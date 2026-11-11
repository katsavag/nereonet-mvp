package com.nereonix.nereonet_mvp.core.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// An upload over the multipart limits (application.yml) cannot be read at all, so the CSRF check would fail with
// an error page. Runs just before the CSRF check (see SecurityConfig) and sends the user back to the same URL with
// ?fileTooLarge, where the upload form shows the error. Every upload form has a GET page at its POST URL.
public class UploadTooLargeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadTooLargeFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
            try {
                request.getParts();
            } catch (IllegalStateException e) {
                // Tomcat reports the size limits this way. The path was checked by the Spring Security firewall.
                log.info("Upload rejected, too large: {}", request.getServletPath());
                response.sendRedirect(request.getContextPath() + request.getServletPath() + "?fileTooLarge");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
