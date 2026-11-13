package com.nereonix.nereonet_mvp.requirement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.certificate.CertificateForm;
import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * The office's required documents (P42), loaded once per page, and how they apply to one seafarer:
 * <ul>
 *   <li>a requirement applies to a person with a rank on a vessel when its ranks include the rank and its vessel type
 *   is "all vessels" or the vessel's type. Without a rank or a vessel nothing applies;</li>
 *   <li>rows of the same type (the all-vessels row and the vessel type's) are one document: required when any of them
 *   is, optional only when all are; the vessel type's note before the all-vessels one;</li>
 *   <li>a document is held by the certificate of the same type (P20: equal after strip() and toLowerCase) that expires
 *   last. A certificate whose expiry date is past the P39 limit (more than CertificateForm.EXPIRES_YEARS years ahead,
 *   a typing slip from before P39) does not count: the document is Missing until the date is corrected.</li>
 * </ul>
 * PeopleQueries applies the same rules in SQL for the Crew list's sort and filter.
 */
public final class RequirementRules {

    private static final Comparator<RequiredDocument> ORDER = Comparator
            .comparing(RequiredDocument::optional)
            .thenComparing(RequiredDocument::state)
            .thenComparing(RequiredDocument::type, String.CASE_INSENSITIVE_ORDER);

    private final List<DocumentRequirement> requirements;

    public RequirementRules(List<DocumentRequirement> requirements) {
        this.requirements = List.copyOf(requirements);
    }

    public boolean isEmpty() {
        return requirements.stream().allMatch(r -> r.getRanks().isEmpty());
    }

    /** One document as it applies to a person: the merged rows of one type. */
    record Applicable(String key, String type, boolean optional, String note) {
    }

    /** The documents that apply to this rank on this vessel; none without either. */
    List<Applicable> applicable(Rank rank, Ship ship) {
        if (rank == null || ship == null) {
            return List.of();
        }
        Map<String, Applicable> byType = new LinkedHashMap<>();
        for (DocumentRequirement r : requirements) {
            if (!r.appliesTo(rank) || (r.getShipType() != null && r.getShipType() != ship.getType())) {
                continue;
            }
            String key = CertificateTypes.sameTypeKey(r.getCertificateType());
            Applicable before = byType.get(key);
            if (before == null) {
                byType.put(key, new Applicable(key, r.getCertificateType(), r.isOptional(), r.getNote()));
            } else {
                // The vessel type's own note wins over the all-vessels one.
                String note = r.getShipType() != null && r.getNote() != null ? r.getNote()
                        : before.note() != null ? before.note() : r.getNote();
                byType.put(key, new Applicable(key, before.type(), before.optional() && r.isOptional(), note));
            }
        }
        return new ArrayList<>(byType.values());
    }

    /** Where this person stands. certificates: theirs (any order, others are ignored); today: the UTC date. */
    public Compliance compliance(User user, List<Certificate> certificates, LocalDate today) {
        List<Applicable> applicable = applicable(user.getRank(), user.getShip());
        if (applicable.isEmpty()) {
            return new Compliance(user, List.of());
        }
        LocalDate latest = latestExpiry(today);
        Map<String, Certificate> held = new LinkedHashMap<>();
        Comparator<Certificate> later = Comparator.comparing(Certificate::getExpiresOn).thenComparing(Certificate::getId);
        for (Certificate c : certificates) {
            if (c.getUser().getId().equals(user.getId()) && !c.getExpiresOn().isAfter(latest)) {
                held.merge(CertificateTypes.sameTypeKey(c.getType()), c, (a, b) -> later.compare(a, b) >= 0 ? a : b);
            }
        }
        List<RequiredDocument> documents = new ArrayList<>();
        for (Applicable a : applicable) {
            Certificate certificate = held.get(a.key());
            documents.add(new RequiredDocument(a.type(), a.optional(), a.note(),
                    RequirementState.of(certificate, today), certificate));
        }
        documents.sort(ORDER);
        return new Compliance(user, List.copyOf(documents));
    }

    /** The latest expiry date that counts: the P39 limit of the certificate form. */
    public static LocalDate latestExpiry(LocalDate today) {
        return today.plusYears(CertificateForm.EXPIRES_YEARS);
    }
}
