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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        return new FileSystemResource(inside(storagePath));
    }

    /**
     * Deletes the file once the current transaction has committed (at once without one), so a change that is rolled
     * back keeps its file. A file already gone is fine. (P51: a receipt file the Master removes before confirming.)
     */
    public void deleteLater(String storagePath) {
        Path file = inside(storagePath);
        Runnable delete = () -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    private Path inside(String storagePath) {
        Path file = dir.resolve(storagePath).normalize();
        if (!dir.equals(file.getParent())) {
            throw new IllegalArgumentException("Storage path outside the library folder");
        }
        return file;
    }
}
