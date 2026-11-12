package com.nereonix.nereonet_mvp.core.profile;

import java.time.Duration;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

// A profile photo file (P31), for the crew (/ship/crew/{id}/photo/...) and the office (/office/crew/{id}/photo/...).
// The URL carries the photo's key, which changes with every new photo, so the browser may keep it for a year without
// asking again (over a satellite link); "private": only the signed-in person's browser, never a shared cache.
public final class PhotoResponse {

    private PhotoResponse() {
    }

    /** The size in a photo URL, "512" or "96"; anything else is a 404 (the same as a photo that does not exist). */
    public static int size(String value) {
        if (String.valueOf(PhotoImages.LARGE).equals(value)) {
            return PhotoImages.LARGE;
        }
        if (String.valueOf(PhotoImages.SMALL).equals(value)) {
            return PhotoImages.SMALL;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public static ResponseEntity<Resource> of(Resource file) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(file);
    }
}
