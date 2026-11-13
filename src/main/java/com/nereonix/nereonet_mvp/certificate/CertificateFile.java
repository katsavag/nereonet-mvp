package com.nereonix.nereonet_mvp.certificate;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The scan of a certificate (P39): its file name, content type (checked on upload: PDF, JPEG or PNG) and content.
 * Opened in the browser (inline) under its own name; never kept by a cache, as it is personal and the crew may use a
 * shared PC. Spring Security sends X-Content-Type-Options: nosniff, so the browser keeps the stored type.
 */
public record CertificateFile(String fileName, String contentType, Resource content) {

    public ResponseEntity<Resource> response() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noStore())
                .body(content);
    }
}
