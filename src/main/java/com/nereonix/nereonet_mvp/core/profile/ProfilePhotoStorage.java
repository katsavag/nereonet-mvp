package com.nereonix.nereonet_mvp.core.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Profile photos on disk (P31), in the folder "profile-photos" inside the existing file storage
 * (app.library.storage-dir), never under static/: they are served by a controller, behind sign-in and only to the
 * people allowed to see them. Each photo is two files, "<key>-512.jpg" and "<key>-96.jpg"; the key is a new UUID for
 * every upload, never anything the user sent.
 */
@Component
public class ProfilePhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path dir;

    public ProfilePhotoStorage(@Value("${app.library.storage-dir}") String storageDir) {
        this.dir = Path.of(storageDir).toAbsolutePath().normalize().resolve("profile-photos");
    }

    /** Saves both sizes under a new key and returns it. */
    public String save(PhotoImages.Photo photo) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.write(file(key, PhotoImages.LARGE), photo.large());
            Files.write(file(key, PhotoImages.SMALL), photo.small());
        } catch (IOException e) {
            delete(key);
            throw new UncheckedIOException(e);
        }
        return key;
    }

    /** One size of a photo; null when the key or size is not one of ours or the file is gone. */
    public Resource load(String key, int size) {
        if (!isKey(key) || (size != PhotoImages.LARGE && size != PhotoImages.SMALL)) {
            return null;
        }
        Path file = file(key, size);
        return Files.isRegularFile(file) ? new FileSystemResource(file) : null;
    }

    /** Removes both files once the current transaction has committed (at once without one), so a rollback never
     *  leaves a profile pointing at deleted files. */
    public void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(key);
                }
            });
        } else {
            delete(key);
        }
    }

    private void delete(String key) {
        if (!isKey(key)) {
            return;
        }
        for (int size : new int[] {PhotoImages.LARGE, PhotoImages.SMALL}) {
            try {
                Files.deleteIfExists(file(key, size));
            } catch (IOException e) {
                log.warn("Could not delete profile photo file {}-{}: {}", key, size, e.getMessage());
            }
        }
    }

    private Path file(String key, int size) {
        return dir.resolve(key + "-" + size + ".jpg");
    }

    private static boolean isKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }
}
