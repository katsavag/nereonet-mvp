package com.nereonix.nereonet_mvp.shiphome;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.library.AcknowledgementCounts;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// What the crew home page (/ship, P26) shows. Read only: nothing here changes anything.
@Service
public class ShipHomeService {

    /**
     * user: the signed-in ship user (name, rank, ship).
     * toRead: their Required library items not acknowledged in the current version, oldest first.
     * certificates: their own certificates, soonest expiry first (so expired, then expiring soon).
     * crewPending, crewCounts: the Master only (null for everyone else): the Required items not yet acknowledged by
     * everyone on board, oldest first, and the counts per item.
     */
    public record Home(User user, Ship ship, List<ToRead> toRead, List<Certificate> certificates,
                       List<LibraryItem> crewPending, AcknowledgementCounts crewCounts) {

        public boolean master() {
            return crewPending != null;
        }
    }

    private final UserRepository userRepository;
    private final CertificateRepository certificateRepository;
    private final LibraryService libraryService;

    public ShipHomeService(UserRepository userRepository, CertificateRepository certificateRepository,
                           LibraryService libraryService) {
        this.userRepository = userRepository;
        this.certificateRepository = certificateRepository;
        this.libraryService = libraryService;
    }

    @Transactional(readOnly = true)
    public Home load(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        Ship ship = user.getShip();
        List<LibraryItem> required = libraryService.requiredItems(ship);
        Set<Long> acknowledged = libraryService.acknowledgedItemIds(email);
        List<ToRead> toRead = required.stream()
                .filter(item -> !acknowledged.contains(item.getId()))
                .map(ToRead::of)
                .toList();
        List<Certificate> certificates = certificateRepository.findAllByUserIdOrderByExpiresOnAsc(user.getId());
        if (user.getRole() != Role.MASTER) {
            return new Home(user, ship, toRead, certificates, null, null);
        }
        AcknowledgementCounts counts = libraryService.acknowledgementCounts(ship);
        List<LibraryItem> pending = required.stream().filter(item -> !counts.completeFor(item.getId())).toList();
        return new Home(user, ship, toRead, certificates, pending, counts);
    }
}
