package com.nereonix.nereonet_mvp.core.importfile;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// A confirmed column and value mapping (P24), saved when an import is done and suggested again for a file of the
// same kind with the same column names (in any order). Never holds file contents other than column names and the
// values that were mapped (e.g. the rank "C/E").
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "headerKey"}))
@Getter
@Setter
public class ImportMapping {

    static final int VALUE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "crew" or "item" (ImportTarget.kind).
    @Column(nullable = false, length = 20)
    private String kind;

    // SHA-256 (hex) of the file's column names, normalized and sorted (ImportMappingService.headerKey).
    @Column(nullable = false, length = 64)
    private String headerKey;

    // Field key (or second key, "fullName2") -> the normalized name of its column.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_mapping_column", joinColumns = @JoinColumn(name = "mapping_id"))
    @MapKeyColumn(name = "field_key", length = 40)
    @Column(name = "column_name", nullable = false, length = VALUE_MAX_LENGTH)
    private Map<String, String> columns = new HashMap<>();

    // "rank|ce" (field key, ImportNames.compact of the file's value) -> the canonical value chosen ("CHIEF_ENGINEER").
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_mapping_value", joinColumns = @JoinColumn(name = "mapping_id"))
    @MapKeyColumn(name = "file_value", length = VALUE_MAX_LENGTH + 41)
    @Column(name = "chosen", nullable = false, length = 100)
    private Map<String, String> chosenValues = new HashMap<>();

    @Column(nullable = false)
    private Instant updatedAt;
}
