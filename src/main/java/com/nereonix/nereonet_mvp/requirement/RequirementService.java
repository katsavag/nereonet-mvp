package com.nereonix.nereonet_mvp.requirement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The office's Required documents page (P42): the grid of one vessel type, and saving it.
 * Rows: every type with a requirement on any vessel type, the starter types (CertificateTypes.STARTER) and the types
 * of the certificates on record, one row per type (P20 matching), the starter types first in their order, then the
 * others by name. A type the office asks to add (?add=) is shown first until saved.
 * Saving a vessel type replaces its rows: a row with ranks, a note or Optional is kept, any other row of that type
 * is deleted; every row of that vessel type stops being a suggestion. Recorded as REQUIREMENTS_UPDATED with what
 * changed.
 */
@Service
public class RequirementService {

    /** The URL value for All vessels. */
    public static final String ALL = "all";

    private final DocumentRequirementRepository requirementRepository;
    private final CertificateRepository certificateRepository;
    private final AuditService auditService;

    public RequirementService(DocumentRequirementRepository requirementRepository,
                              CertificateRepository certificateRepository, AuditService auditService) {
        this.requirementRepository = requirementRepository;
        this.certificateRepository = certificateRepository;
        this.auditService = auditService;
    }

    /** The vessel type of a URL value: null (All vessels) for "all", nothing or anything unknown. */
    public static ShipType vesselType(String key) {
        return key == null ? null
                : Arrays.stream(ShipType.values()).filter(t -> t.name().equals(key)).findFirst().orElse(null);
    }

    public static String key(ShipType vesselType) {
        return vesselType == null ? ALL : vesselType.name();
    }

    /**
     * The grid of one vessel type (null: All vessels). add: a type to show as a new row (blank: none); when it is a
     * type already on the grid, that row is marked instead.
     */
    @Transactional(readOnly = true)
    public RequirementGrid grid(ShipType vesselType, String add) {
        return grid(vesselType, add, null);
    }

    /** The grid as posted (a note too long): the posted ticks, notes and Optional over the saved ones. */
    @Transactional(readOnly = true)
    public RequirementGrid grid(ShipType vesselType, RequirementGridForm posted) {
        return grid(vesselType, null, posted);
    }

    private RequirementGrid grid(ShipType vesselType, String add, RequirementGridForm posted) {
        List<DocumentRequirement> all = requirementRepository.findAllWithRanks();
        List<CertificateRepository.TypeCount> used = certificateRepository.countByType();
        Map<String, DocumentRequirement> own = byKey(all, vesselType);
        Map<String, DocumentRequirement> general = vesselType == null ? Map.of() : byKey(all, null);

        Map<String, String> types = types(all, used);
        String addKey = null;
        if (add != null && !add.isBlank() && add.strip().length() <= Certificate.TYPE_MAX_LENGTH) {
            addKey = CertificateTypes.sameTypeKey(add);
            types.putIfAbsent(addKey, CertificateTypes.canonical(add, used));
        }
        Map<String, RequirementGridForm.RowForm> sent = new LinkedHashMap<>();
        if (posted != null) {
            posted.getRows().stream().filter(r -> r.getType() != null && !r.getType().isBlank())
                    .forEach(r -> sent.put(CertificateTypes.sameTypeKey(r.getType()), r));
            sent.forEach((key, r) -> types.putIfAbsent(key, r.getType()));
        }

        List<RequirementGrid.Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> type : types.entrySet()) {
            String key = type.getKey();
            DocumentRequirement saved = own.get(key);
            DocumentRequirement inherited = general.get(key);
            RequirementGridForm.RowForm form = sent.get(key);
            Set<Rank> ranks = form != null ? EnumSet.copyOf(withNone(form.getRanks()))
                    : saved != null ? EnumSet.copyOf(withNone(saved.getRanks())) : EnumSet.noneOf(Rank.class);
            rows.add(new RequirementGrid.Row(type.getValue(), ranks,
                    inherited == null ? Set.of() : EnumSet.copyOf(withNone(inherited.getRanks())),
                    form != null ? form.isOptional() : saved != null && saved.isOptional(),
                    form != null ? form.getNote() : saved == null ? null : saved.getNote(),
                    saved != null && saved.isSuggestion(), key.equals(addKey) && saved == null));
        }
        // The row just asked for comes first, so it is in view.
        rows.sort(Comparator.comparing(row -> !row.added()));
        boolean suggestions = all.stream().anyMatch(DocumentRequirement::isSuggestion);
        return new RequirementGrid(vesselType, Rank.groups(), rows, suggestions);
    }

    private static Set<Rank> withNone(Set<Rank> ranks) {
        return ranks.isEmpty() ? EnumSet.noneOf(Rank.class) : ranks;
    }

    // Every type the grid lists, key to spelling: the starter types in their order, then the others by name. A
    // requirement's own spelling wins, then the most used spelling of the certificates on record.
    private static Map<String, String> types(List<DocumentRequirement> requirements,
                                             List<CertificateRepository.TypeCount> used) {
        Map<String, String> spelling = new LinkedHashMap<>();
        requirements.forEach(r -> spelling.putIfAbsent(CertificateTypes.sameTypeKey(r.getCertificateType()),
                r.getCertificateType()));
        CertificateTypes.suggestions(used).forEach(s -> spelling.putIfAbsent(CertificateTypes.sameTypeKey(s), s));

        Map<String, String> ordered = new LinkedHashMap<>();
        CertificateTypes.STARTER.forEach(s -> {
            String key = CertificateTypes.sameTypeKey(s);
            ordered.put(key, spelling.getOrDefault(key, s));
        });
        spelling.entrySet().stream()
                .filter(e -> !ordered.containsKey(e.getKey()))
                .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    private static Map<String, DocumentRequirement> byKey(List<DocumentRequirement> all, ShipType vesselType) {
        return all.stream().filter(r -> r.getShipType() == vesselType)
                .collect(Collectors.toMap(r -> CertificateTypes.sameTypeKey(r.getCertificateType()), r -> r,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Saves the grid of one vessel type (null: All vessels): what changed, by type, goes to the audit log. Returns
     * whether anything changed (a review of the starter template counts).
     */
    @Transactional
    public boolean save(ShipType vesselType, RequirementGridForm form) {
        List<DocumentRequirement> all = requirementRepository.findAllWithRanks();
        Map<String, DocumentRequirement> own = byKey(all, vesselType);
        List<CertificateRepository.TypeCount> used = certificateRepository.countByType();
        List<String> changes = new ArrayList<>();
        boolean reviewed = own.values().stream().anyMatch(DocumentRequirement::isSuggestion);

        Map<String, RequirementGridForm.RowForm> sent = new LinkedHashMap<>();
        for (RequirementGridForm.RowForm row : form.getRows()) {
            if (row.getType() != null && !row.getType().isBlank()) {
                sent.putIfAbsent(CertificateTypes.sameTypeKey(row.getType()), row);
            }
        }
        for (Map.Entry<String, RequirementGridForm.RowForm> entry : sent.entrySet()) {
            RequirementGridForm.RowForm row = entry.getValue();
            DocumentRequirement saved = own.get(entry.getKey());
            Set<Rank> ranks = row.getRanks().isEmpty() ? EnumSet.noneOf(Rank.class) : EnumSet.copyOf(row.getRanks());
            boolean keep = !ranks.isEmpty() || row.getNote() != null || row.isOptional();
            if (saved == null && !keep) {
                continue;
            }
            if (saved != null && !keep) {
                requirementRepository.delete(saved);
                changes.add(saved.getCertificateType() + " removed");
                continue;
            }
            if (saved == null) {
                saved = new DocumentRequirement();
                saved.setCertificateType(CertificateTypes.canonical(row.getType(), used));
                saved.setShipType(vesselType);
                saved.setRanks(EnumSet.noneOf(Rank.class));
                changes.add(saved.getCertificateType() + " added");
            }
            describe(saved, ranks, row, changes);
            saved.setRanks(ranks);
            saved.setOptional(row.isOptional());
            saved.setNote(row.getNote());
            saved.setSuggestion(false);
            requirementRepository.save(saved);
        }
        own.values().forEach(r -> r.setSuggestion(false));

        if (changes.isEmpty() && !reviewed) {
            return false;
        }
        String scope = vesselType == null ? "All vessels" : vesselType.name();
        auditService.record("REQUIREMENTS_UPDATED", null,
                scope + ": " + (changes.isEmpty() ? "reviewed, no changes" : String.join("; ", changes)));
        return true;
    }

    // "Passport +COOK -WIPER", "Medical First Aid optional", "Passport note changed".
    private static void describe(DocumentRequirement saved, Set<Rank> ranks, RequirementGridForm.RowForm row,
                                 List<String> changes) {
        List<String> parts = new ArrayList<>();
        ranks.stream().filter(r -> !saved.getRanks().contains(r)).forEach(r -> parts.add("+" + r.name()));
        saved.getRanks().stream().filter(r -> !ranks.contains(r)).forEach(r -> parts.add("-" + r.name()));
        if (saved.isOptional() != row.isOptional()) {
            parts.add(row.isOptional() ? "optional" : "required");
        }
        if (!Objects.equals(saved.getNote(), row.getNote())) {
            parts.add(row.getNote() == null ? "note removed" : "note \"" + row.getNote() + "\"");
        }
        if (!parts.isEmpty()) {
            changes.add(saved.getCertificateType() + " " + String.join(" ", parts));
        }
    }

    /** The vessel types the grid can show as URL values: "all" first, then each ShipType. */
    public static List<String> vesselTypeKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(ALL);
        Arrays.stream(ShipType.values()).forEach(t -> keys.add(t.name()));
        return keys;
    }
}
