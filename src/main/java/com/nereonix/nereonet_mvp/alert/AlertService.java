package com.nereonix.nereonet_mvp.alert;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.certificate.CertificateService;
import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKey;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.core.vetting.SireInspection;
import com.nereonix.nereonet_mvp.core.vetting.SireInspectionRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireObservationRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireView;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;
import com.nereonix.nereonet_mvp.requirement.Compliance;
import com.nereonix.nereonet_mvp.requirement.ComplianceService;

/**
 * What the snooze page shows for the alerts asked for (P43): each item found again from its key, as it stands right
 * now. An item that is not an alert any more (a renewed certificate, a vessel with new data, a key that names
 * nothing) is left out, so nothing stale is ever snoozed. Items of inactive vessels are left out too: they are not
 * alerts anywhere.
 */
@Service
public class AlertService {

    private final CertificateRepository certificateRepository;
    private final UserRepository userRepository;
    private final ShipRepository shipRepository;
    private final ComplianceService complianceService;
    private final VettingService vettingService;
    private final SireService sireService;
    private final SireInspectionRepository inspectionRepository;
    private final SireObservationRepository observationRepository;

    public AlertService(CertificateRepository certificateRepository, UserRepository userRepository,
                        ShipRepository shipRepository, ComplianceService complianceService,
                        VettingService vettingService, SireService sireService,
                        SireInspectionRepository inspectionRepository, SireObservationRepository observationRepository) {
        this.certificateRepository = certificateRepository;
        this.userRepository = userRepository;
        this.shipRepository = shipRepository;
        this.complianceService = complianceService;
        this.vettingService = vettingService;
        this.sireService = sireService;
        this.inspectionRepository = inspectionRepository;
        this.observationRepository = observationRepository;
    }

    @Transactional(readOnly = true)
    public List<AlertView> describe(List<AlertKey> keys) {
        LocalDate today = VettingService.today();
        List<AlertView> views = new ArrayList<>();
        for (AlertKey key : keys) {
            AlertView view = describe(key, today);
            if (view != null && views.stream().noneMatch(v -> v.alert().key().equals(key))) {
                views.add(view);
            }
        }
        return views;
    }

    private AlertView describe(AlertKey key, LocalDate today) {
        long id = key.subjectId();
        return switch (key.kind()) {
            case CERTIFICATE_EXPIRING, CERTIFICATE_EXPIRED -> certificateRepository.findById(id).map(c -> {
                Alert alert = CertificateService.alertOf(c, today);
                if (alert == null || alert.key().kind() != key.kind() || !active(c.getUser().getShip())) {
                    return null;
                }
                User u = c.getUser();
                return new AlertView(alert, u.getShip(), c.getType(), u.getFullName(), u.getEmail(), c.getExpiresOn(), null);
            }).orElse(null);
            case DOCUMENTS_MISSING -> userRepository.findById(id)
                    .filter(u -> u.getRole() != Role.OFFICE_USER && u.isEnabled() && active(u.getShip())).map(u -> {
                        Compliance compliance = complianceService.of(u);
                        if (compliance.missing() == 0) {
                            return null;
                        }
                        return new AlertView(Alert.of(AlertKind.DOCUMENTS_MISSING, id, (int) -compliance.missing()),
                                u.getShip(), null, u.getFullName(), u.getEmail(), null, compliance.missing());
                    }).orElse(null);
            case VETTING_INSPECTION, VETTING_SCORE -> shipRepository.findById(id).filter(Ship::isActive).map(ship -> {
                RightShipView view = vettingService.view(ship);
                Alert alert = view.alerts(id).stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
                if (alert == null) {
                    return null;
                }
                return key.kind() == AlertKind.VETTING_SCORE
                        ? new AlertView(alert, ship, null, null, null, null, (long) view.score())
                        : new AlertView(alert, ship, null, null, null, view.inspectionValidUntil(), null);
            }).orElse(null);
            case SIRE_COMMENTS, SIRE_REPORT -> inspectionRepository.findById(id)
                    .filter(i -> active(i.getShip()) && latest(i)).map(i -> {
                        SireView view = sireService.view(i);
                        Alert alert = view.alerts().stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
                        if (alert == null) {
                            return null;
                        }
                        LocalDate date = key.kind() == AlertKind.SIRE_COMMENTS ? view.commentsDueOn() : view.currentUntil();
                        return new AlertView(alert, i.getShip(), null, null, null, date, null);
                    }).orElse(null);
            case SIRE_OBSERVATION -> observationRepository.findById(id)
                    .filter(o -> active(o.getInspection().getShip()) && latest(o.getInspection())).map(o -> {
                        SireView view = sireService.view(o.getInspection());
                        Alert alert = view.alerts().stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
                        if (alert == null) {
                            return null;
                        }
                        return new AlertView(alert, o.getInspection().getShip(), o.getQuestionNumber(), null, null,
                                o.getTargetDate(), null);
                    }).orElse(null);
        };
    }

    private static boolean active(Ship ship) {
        return ship != null && ship.isActive();
    }

    // Only a vessel's latest inspection is "its" SIRE 2.0 standing.
    private boolean latest(SireInspection inspection) {
        List<SireInspection> all = sireService.inspections(inspection.getShip().getId());
        return !all.isEmpty() && all.getFirst().getId().equals(inspection.getId());
    }

    /** The observation's inspection, for the links of the snooze page. */
    public static SireInspection inspectionOf(SireObservation observation) {
        return observation.getInspection();
    }
}
