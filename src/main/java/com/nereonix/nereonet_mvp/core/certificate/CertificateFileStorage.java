package com.nereonix.nereonet_mvp.core.certificate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * The scans of certificates (P39) on disk, like the library's files (LibraryFileStorage): in
 * app.certificate.storage-dir (by default a "certificates" folder inside the library's folder), each under a
 * generated name; the uploaded file name is only kept in Certificate.fileName. A file is a PDF, JPEG or PNG of at
 * most 10 MB (the multipart limit in application.yml): its extension says which, and its first bytes must agree,
 * so a renamed file of another kind is refused.
 */
@Component
public class CertificateFileStorage {

    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // The extensions accepted, and the content type each one is stored and served with.
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    // How each type's content starts.
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", new byte[] {'%', 'P', 'D', 'F'},
            "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    private final Path dir;

    public CertificateFileStorage(
            @Value("${app.certificate.storage-dir:${app.library.storage-dir}/certificates}") String storageDir) {
        this.dir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    /** The content type of an accepted file (PDF, JPEG or PNG by its extension and its first bytes), else null. */
    public static String contentTypeOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String type = extension == null ? null : CONTENT_TYPES.get(extension.toLowerCase(Locale.ROOT));
        if (type == null) {
            return null;
        }
        byte[] signature = SIGNATURES.get(type);
        try (InputStream in = file.getInputStream()) {
            return Arrays.equals(in.readNBytes(signature.length), signature) ? type : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves the content under a new generated name and returns that name (the certificate's fileStoragePath). */
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
     * back keeps its file. A file already gone is fine.
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
            throw new IllegalArgumentException("Storage path outside the certificate folder");
        }
        return file;
    }
}
