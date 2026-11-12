package com.nereonix.nereonet_mvp.core.library;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// The file of a library item's current version, for the crew (library) and the office (officelibrary) (P22).
// A PDF opens in the browser (inline) unless download is asked for; DOCX and XLSX are always downloads (attachment).
// The content type is the stored one, checked against the extension on upload (LibraryItemForm); Spring Security
// sends X-Content-Type-Options: nosniff, so the browser does not guess another.
public final class LibraryFileResponse {

    private LibraryFileResponse() {
    }

    public static ResponseEntity<Resource> of(LibraryItemVersion version, Resource file, boolean download) {
        ContentDisposition.Builder disposition = version.isPdf() && !download
                ? ContentDisposition.inline() : ContentDisposition.attachment();
        MediaType type = version.getFileContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(version.getFileContentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(version.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(type)
                .body(file);
    }
}
