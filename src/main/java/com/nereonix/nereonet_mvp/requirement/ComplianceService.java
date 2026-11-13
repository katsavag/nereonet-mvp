package com.nereonix.nereonet_mvp.requirement;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * Who is missing what (P42), worked out on every read from the requirements and the certificates: nothing derived is
 * stored, so a changed requirement shows everywhere on the next page load. Each method runs a fixed number of
 * queries: the requirements with their ranks (one), then the certificates of the people asked about (one; none when
 * no requirement is set).
 */
@Service
public class ComplianceService {

    private final DocumentRequirementRepository requirementRepository;
    private final CertificateRepository certificateRepository;
    private final UserRepository userRepository;

    public ComplianceService(DocumentRequirementRepository requirementRepository,
                             CertificateRepository certificateRepository, UserRepository userRepository) {
        this.requirementRepository = requirementRepository;
        this.certificateRepository = certificateRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public RequirementRules rules() {
        return new RequirementRules(requirementRepository.findAllWithRanks());
    }

    /** One person: the crew member's page in the office, My profile and Home on board. Two queries. */
    @Transactional(readOnly = true)
    public Compliance of(User user) {
        RequirementRules rules = rules();
        List<Certificate> certificates = rules.isEmpty() ? List.of()
                : certificateRepository.findAllByUserIdOrderByExpiresOnAsc(user.getId());
        return rules.compliance(user, certificates, today());
    }

    /** Several people (a page of the Crew list, a vessel's crew), by user id, in the order given. Two queries. */
    @Transactional(readOnly = true)
    public Map<Long, Compliance> of(Collection<User> users) {
        return of(rules(), users);
    }

    /** As of(users), with rules already loaded. One query, none without requirements or people. */
    @Transactional(readOnly = true)
    public Map<Long, Compliance> of(RequirementRules rules, Collection<User> users) {
        LocalDate today = today();
        Map<Long, List<Certificate>> byUser = rules.isEmpty() || users.isEmpty() ? Map.of()
                : certificateRepository.findAllByUserIdIn(users.stream().map(User::getId).toList()).stream()
                        .collect(Collectors.groupingBy(c -> c.getUser().getId()));
        Map<Long, Compliance> result = new LinkedHashMap<>();
        for (User user : users) {
            result.put(user.getId(), rules.compliance(user, byUser.getOrDefault(user.getId(), List.of()), today));
        }
        return result;
    }

    /**
     * The enabled crew with a rank on the active vessels and where each stands, for the Overview: one query for the
     * requirements, and only when any is set, one for the crew (with their vessels) and one for their certificates.
     */
    @Transactional(readOnly = true)
    public List<Compliance> onBoard() {
        RequirementRules rules = rules();
        if (rules.isEmpty()) {
            return List.of();
        }
        return List.copyOf(of(rules, userRepository.findRankedCrewOnActiveShips(Role.OFFICE_USER)).values());
    }

    /** The UTC date, as the certificate badges and the reminder job. */
    public LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
