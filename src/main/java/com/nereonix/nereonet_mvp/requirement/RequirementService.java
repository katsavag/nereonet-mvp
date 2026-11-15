package com.nereonix.nereonet_mvp.requirement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The office's Required documents page: the grid of one vessel (of one vessel type before), and saving it.
 * Rows: every type with a requirement on any vessel, the starter types (CertificateTypes.STARTER) and the types of the
 * certificates on record, one row per type (P20 matching), the starter types first in their order, then the others by
 * name. A type the office asks to add (?add=) is shown first until saved.
 * Each vessel has its own requirements, starting with the defaults of its type (StarterRequirements.defaults, copied
 * by startWithDefaults when the vessel is created); saving, Delete row and "Reset to Bulk carrier defaults" change
 * that vessel only. Saving replaces the vessel's rows: a row with ranks, a note or Optional is kept, any other row is
 * deleted; every row of the vessel stops being a suggestion. Recorded as REQUIREMENTS_UPDATED ("ALDER: Passport
 * +COOK") or REQUIREMENTS_RESET, on the vessel.
 */
@Service
public class RequirementService {

    private final DocumentRequirementRepository requirementRepository;
    private final CertificateRepository certificateRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final MessageSource messageSource;

    public RequirementService(DocumentRequirementRepository requirementRepository,
                              CertificateRepository certificateRepository, ShipRepository shipRepository,
                              AuditService auditService, MessageSource messageSource) {
        this.requirementRepository = requirementRepository;
        this.certificateRepository = certificateRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.messageSource = messageSource;
    }

    /**
     * What the vessel filter chose (active vessels only): the vessel with this id, else the first by name (none
     * without active vessels). One query: the active vessels, which are also the filter's options, each named with
     * its type in the given language ("ALDER · Bulk carrier").
     */
    @Transactional(readOnly = true)
    public RequirementSelection select(String vessel, Locale locale) {
        List<Ship> active = shipRepository.findAllByActiveTrueOrderByNameAsc();
        Long id = parseId(vessel);
        Ship ship = active.stream().filter(s -> s.getId().equals(id)).findFirst()
                .orElse(active.isEmpty() ? null : active.getFirst());
        List<RequirementSelection.Option> options = active.stream().map(s -> new RequirementSelection.Option(
                String.valueOf(s.getId()), s.getName(), s.getType() == null
                        ? messageSource.getMessage("requirement.filter.noType", null, locale)
                        : messageSource.getMessage("ship.type." + s.getType().name(), null, locale))).toList();
        return new RequirementSelection(ship, options);
    }

    /** An active vessel by its id as posted, or null (unknown, inactive or not a number). */
    @Transactional(readOnly = true)
    public Ship activeVessel(String vessel) {
        Long id = parseId(vessel);
        return id == null ? null : shipRepository.findById(id).filter(Ship::isActive).orElse(null);
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A message in the request's language. */
    public String label(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** The name of the vessel's defaults in the request's language: its type ("Bulk carrier"), or null without one. */
    public String defaultsName(Ship ship) {
        return ship == null || ship.getType() == null ? null : label("ship.type." + ship.getType().name());
    }

    /**
     * Removes the certificate type's requirement from one vessel only (P58: the shown vessel). Recorded as
     * "Required documents changed" ("ALDER: Tanker Endorsement removed"). Returns false when there was none.
     */
    @Transactional
    public boolean delete(Ship ship, String certificateType) {
        if (ship == null || certificateType == null || certificateType.isBlank()) {
            return false;
        }
        DocumentRequirement saved = byKey(own(ship)).get(CertificateTypes.sameTypeKey(certificateType));
        if (saved == null) {
            return false;
        }
        requirementRepository.delete(saved);
        auditService.record("REQUIREMENTS_UPDATED", ship, ship.getName() + ": " + saved.getCertificateType() + " removed");
        return true;
    }

    /**
     * The grid of one vessel. add: a type to show as a new row (blank: none); when it is a type already on the grid,
     * that row is marked instead.
     */
    @Transactional(readOnly = true)
    public RequirementGrid grid(Ship ship, String add) {
        return grid(ship, add, null);
    }

    /** The grid as posted (a note too long): the posted ticks, notes and Optional over the saved ones. */
    @Transactional(readOnly = true)
    public RequirementGrid grid(Ship ship, RequirementGridForm posted) {
        return grid(ship, null, posted);
    }

    private RequirementGrid grid(Ship ship, String add, RequirementGridForm posted) {
        List<DocumentRequirement> all = requirementRepository.findAllWithRanks();
        List<CertificateRepository.TypeCount> used = certificateRepository.countByType();
        List<DocumentRequirement> mine = all.stream().filter(r -> r.isFor(ship)).toList();
        Map<String, DocumentRequirement> own = byKey(mine);
        Map<String, StarterRequirements.Default> defaults = defaults(ship);

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
            RequirementGridForm.RowForm form = sent.get(key);
            Set<Rank> ranks = form != null ? EnumSet.copyOf(withNone(form.getRanks()))
                    : saved != null ? EnumSet.copyOf(withNone(saved.getRanks())) : EnumSet.noneOf(Rank.class);
            boolean optional = form != null ? form.isOptional() : saved != null && saved.isOptional();
            String note = form != null ? form.getNote() : saved == null ? null : saved.getNote();
            rows.add(new RequirementGrid.Row(type.getValue(), ranks, optional, note,
                    saved != null && saved.isSuggestion(), key.equals(addKey) && saved == null,
                    differs(ranks, optional, note, defaults.get(key))));
        }
        // The row just asked for comes first, so it is in view.
        rows.sort(Comparator.comparing(row -> !row.added()));
        boolean suggestions = mine.stream().anyMatch(DocumentRequirement::isSuggestion);
        return new RequirementGrid(ship, Rank.groups(), rows, suggestions,
                ship == null ? List.of() : resetChanges(mine, defaults).stream().map(Change::label).toList());
    }

    private static Set<Rank> withNone(Set<Rank> ranks) {
        return ranks.isEmpty() ? EnumSet.noneOf(Rank.class) : ranks;
    }

    // Whether a row differs from the default of its type (none: any rank, Optional or note differs).
    private static boolean differs(Set<Rank> ranks, boolean optional, String note, StarterRequirements.Default def) {
        if (def == null) {
            return !ranks.isEmpty() || optional || note != null;
        }
        return !ranks.equals(def.ranks()) || optional || note != null;
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

    private List<DocumentRequirement> own(Ship ship) {
        return requirementRepository.findAllByShipIdWithRanks(ship.getId());
    }

    private static Map<String, DocumentRequirement> byKey(List<DocumentRequirement> rows) {
        return rows.stream().collect(Collectors.toMap(r -> CertificateTypes.sameTypeKey(r.getCertificateType()),
                r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    // The defaults of the vessel's type, by type key, in the template's order.
    private static Map<String, StarterRequirements.Default> defaults(Ship ship) {
        Map<String, StarterRequirements.Default> byKey = new LinkedHashMap<>();
        if (ship != null) {
            StarterRequirements.defaults(ship.getType())
                    .forEach(d -> byKey.put(CertificateTypes.sameTypeKey(d.type()), d));
        }
        return byKey;
    }

    /**
     * Saves the grid of one vessel: what changed, by type, goes to the audit log ("ALDER: Passport +COOK"). Returns
     * whether anything changed (a review of the defaults it was given counts).
     */
    @Transactional
    public boolean save(Ship ship, RequirementGridForm form) {
        Map<String, DocumentRequirement> own = byKey(own(ship));
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
            // The row toggle sent without JavaScript: every rank, on this vessel.
            Set<Rank> ranks = row.isAll() ? EnumSet.allOf(Rank.class)
                    : row.getRanks().isEmpty() ? EnumSet.noneOf(Rank.class) : EnumSet.copyOf(row.getRanks());
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
                saved.setShip(ship);
                saved.setRanks(EnumSet.noneOf(Rank.class));
                changes.add(saved.getCertificateType() + " added");
            }
            describe(saved, ranks, row.isOptional(), row.getNote(), changes);
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
        auditService.record("REQUIREMENTS_UPDATED", ship,
                ship.getName() + ": " + (changes.isEmpty() ? "reviewed, no changes" : String.join("; ", changes)));
        return true;
    }

    // "Passport +COOK -WIPER", "Medical First Aid optional", "Passport note changed".
    private static void describe(DocumentRequirement saved, Set<Rank> ranks, boolean optional, String note,
                                 List<String> changes) {
        List<String> parts = new ArrayList<>();
        ranks.stream().filter(r -> !saved.getRanks().contains(r)).forEach(r -> parts.add("+" + r.name()));
        saved.getRanks().stream().filter(r -> !ranks.contains(r)).forEach(r -> parts.add("-" + r.name()));
        if (saved.isOptional() != optional) {
            parts.add(optional ? "optional" : "required");
        }
        if (!Objects.equals(saved.getNote(), note)) {
            parts.add(note == null ? "note removed" : "note \"" + note + "\"");
        }
        if (!parts.isEmpty()) {
            changes.add(saved.getCertificateType() + " " + String.join(" ", parts));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The defaults of the vessel's type

    /** One difference "Reset to Bulk carrier defaults" undoes: the type and what happens to it. */
    record Change(String type, Kind kind, String label) {

        enum Kind { ADDED, REMOVED, RESTORED }

        // For the audit log: "Passport restored", "Yellow Fever Certificate removed".
        String english() {
            return type + " " + kind.name().toLowerCase(Locale.ROOT);
        }
    }

    /** What resetting the vessel would change, one line each in the request's language; empty when nothing would. */
    @Transactional(readOnly = true)
    public List<String> resetChanges(Ship ship) {
        return resetChanges(own(ship), defaults(ship)).stream().map(Change::label).toList();
    }

    // The defaults' types first (in the template's order), then the vessel's other rows by name.
    private List<Change> resetChanges(List<DocumentRequirement> mine, Map<String, StarterRequirements.Default> defaults) {
        Map<String, DocumentRequirement> own = byKey(mine);
        Set<String> keys = new LinkedHashSet<>(defaults.keySet());
        own.entrySet().stream().sorted(Comparator.comparing(e -> e.getValue().getCertificateType(),
                String.CASE_INSENSITIVE_ORDER)).forEach(e -> keys.add(e.getKey()));
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            DocumentRequirement saved = own.get(key);
            StarterRequirements.Default def = defaults.get(key);
            if (saved == null) {
                changes.add(change(def.type(), Change.Kind.ADDED));
            } else if (def == null) {
                changes.add(change(saved.getCertificateType(), Change.Kind.REMOVED));
            } else if (differs(saved.getRanks(), saved.isOptional(), saved.getNote(), def)) {
                changes.add(change(saved.getCertificateType(), Change.Kind.RESTORED));
            }
        }
        return changes;
    }

    private Change change(String type, Change.Kind kind) {
        return new Change(type, kind,
                label("requirement.reset.change." + kind.name().toLowerCase(Locale.ROOT), type));
    }

    /**
     * Gives a vessel that has no requirement yet the defaults of its type, as suggestions to review (a new vessel,
     * P58). Returns the number of rows added; none when the vessel has requirements already.
     */
    @Transactional
    public int startWithDefaults(Ship ship) {
        if (!own(ship).isEmpty()) {
            return 0;
        }
        return copyDefaults(ship, true);
    }

    private int copyDefaults(Ship ship, boolean suggestion) {
        List<DocumentRequirement> rows = new ArrayList<>();
        for (StarterRequirements.Default d : StarterRequirements.defaults(ship.getType())) {
            DocumentRequirement r = new DocumentRequirement();
            r.setCertificateType(d.type());
            r.setShip(ship);
            r.setRanks(EnumSet.copyOf(d.ranks()));
            r.setSuggestion(suggestion);
            rows.add(r);
        }
        requirementRepository.saveAll(rows);
        return rows.size();
    }

    /**
     * "Reset to Bulk carrier defaults": the vessel's requirements become the defaults of its type again (its
     * other rows, notes and Optional go). Recorded as REQUIREMENTS_RESET on the vessel ("ALDER: reset to the
     * BULK_CARRIER defaults: Passport restored; Yellow Fever Certificate removed"). Returns false, recording nothing,
     * when the vessel already had them.
     */
    @Transactional
    public boolean reset(Ship ship) {
        List<DocumentRequirement> mine = own(ship);
        List<Change> changes = resetChanges(mine, defaults(ship));
        if (changes.isEmpty()) {
            return false;
        }
        requirementRepository.deleteAll(mine);
        requirementRepository.flush();
        copyDefaults(ship, false);
        auditService.record("REQUIREMENTS_RESET", ship, ship.getName() + ": reset to the "
                + (ship.getType() == null ? "general" : ship.getType().name()) + " defaults: "
                + changes.stream().map(Change::english).collect(Collectors.joining("; ")));
        return true;
    }
}
