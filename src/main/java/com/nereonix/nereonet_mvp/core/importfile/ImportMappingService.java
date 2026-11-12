package com.nereonix.nereonet_mvp.core.importfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Saved column and value mappings (P24): one per kind of import and set of column names.
@Service
public class ImportMappingService {

    private final ImportMappingRepository repository;

    public ImportMappingService(ImportMappingRepository repository) {
        this.repository = repository;
    }

    /**
     * Fills in the mapping saved for a file of this kind with the same column names, if there is one. Returns whether
     * there was. Columns whose saved name is not in the file stay as guessed.
     */
    @Transactional(readOnly = true)
    public boolean applySaved(String kind, ImportUpload upload) {
        Optional<ImportMapping> saved = repository.findByKindAndHeaderKey(kind, headerKey(upload.header()));
        if (saved.isEmpty()) {
            return false;
        }
        List<String> names = upload.header().stream().map(ImportNames::normalize).toList();
        Map<String, Integer> columns = upload.getColumns();
        columns.clear();
        saved.get().getColumns().forEach((key, name) -> {
            int column = names.indexOf(name);
            if (column >= 0) {
                columns.put(key, column);
            }
        });
        saved.get().getChosenValues().forEach((key, chosen) -> {
            int bar = key.indexOf('|');
            if (bar > 0) {
                upload.getValues().computeIfAbsent(key.substring(0, bar), k -> new HashMap<>())
                        .put(key.substring(bar + 1), chosen);
            }
        });
        upload.setSavedMappingAt(saved.get().getUpdatedAt());
        return true;
    }

    /** Saves the upload's confirmed mapping for files of this kind with its column names, replacing an earlier one. */
    @Transactional
    public void save(String kind, ImportUpload upload) {
        String key = headerKey(upload.header());
        ImportMapping mapping = repository.findByKindAndHeaderKey(kind, key).orElseGet(ImportMapping::new);
        mapping.setKind(kind);
        mapping.setHeaderKey(key);
        List<String> header = upload.header();
        mapping.getColumns().clear();
        upload.getColumns().forEach((field, column) -> {
            String name = ImportNames.normalize(header.get(column));
            if (!name.isEmpty() && name.length() <= ImportMapping.VALUE_MAX_LENGTH) {
                mapping.getColumns().put(field, name);
            }
        });
        mapping.getChosenValues().clear();
        upload.getValues().forEach((field, values) -> values.forEach((value, chosen) -> {
            if (!chosen.isEmpty() && value.length() <= ImportMapping.VALUE_MAX_LENGTH) {
                mapping.getChosenValues().put(field + "|" + value, chosen);
            }
        }));
        mapping.setUpdatedAt(Instant.now());
        repository.save(mapping);
    }

    /** The same for the same column names in any order, ignoring case, accents, punctuation and empty columns. */
    static String headerKey(List<String> header) {
        String names = header.stream().map(ImportNames::normalize).filter(name -> !name.isEmpty()).sorted()
                .collect(Collectors.joining("\n"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(names.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
