package com.nereonix.nereonet_mvp.core.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// Library files on disk, in app.library.storage-dir. Each file gets a generated name; the uploaded
// file name is never used as a path (it is only kept in LibraryItemVersion.fileName).
@Component
public class LibraryFileStorage {

    private final Path dir;

    public LibraryFileStorage(@Value("${app.library.storage-dir}") String storageDir) {
        this.dir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    /** Saves the content under a new generated name and returns that name (the version's storagePath). */
    public String save(InputStream content) {
        String name = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.copy(content, dir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }

    /** The file saved under this name. Only names directly inside the folder are accepted. */
    public Resource load(String storagePath) {
        Path file = dir.resolve(storagePath).normalize();
        if (!dir.equals(file.getParent())) {
            throw new IllegalArgumentException("Storage path outside the library folder");
        }
        return new FileSystemResource(file);
    }
}
