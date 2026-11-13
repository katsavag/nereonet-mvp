package com.nereonix.nereonet_mvp.shiphome;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// What the crew home page (/ship, P26) shows to the Chief cook and Crew members (the Master has masterhome). Read only:
// nothing here changes anything.
@Service
public class ShipHomeService {

    /**
     * user: the signed-in ship user (name, rank, ship).
     * photoUrl: their profile photo's 96px URL (P31, CrewProfile.photoUrl), for the header; null without one.
     * toRead: their Required library items not acknowledged in the current version and the announcements they have
     * not acknowledged (P27), together, oldest first.
     * certificates: their own certificates, soonest expiry first (so expired, then expiring soon).
     */
    public record Home(User user, Ship ship, String photoUrl, List<ToRead> toRead, List<Certificate> certificates) {
    }

    private final UserRepository userRepository;
    private final CertificateRepository certificateRepository;
    private final LibraryService libraryService;
    private final AnnouncementRecipientRepository recipientRepository;
    private final CrewProfileRepository profileRepository;

    public ShipHomeService(UserRepository userRepository, CertificateRepository certificateRepository,
                           LibraryService libraryService, AnnouncementRecipientRepository recipientRepository,
                           CrewProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.certificateRepository = certificateRepository;
        this.libraryService = libraryService;
        this.recipientRepository = recipientRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public Home load(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        Ship ship = user.getShip();
        List<LibraryItem> required = libraryService.requiredItems(ship);
        Set<Long> acknowledged = libraryService.acknowledgedItemIds(email);
        List<ToRead> toRead = Stream.concat(
                        required.stream().filter(item -> !acknowledged.contains(item.getId())).map(ToRead::of),
                        recipientRepository.findUnacknowledgedForUser(email).stream().map(ToRead::of))
                .sorted(Comparator.comparing(ToRead::date))
                .toList();
        List<Certificate> certificates = certificateRepository.findAllByUserIdOrderByExpiresOnAsc(user.getId());
        String photoUrl = profileRepository.findByUserId(user.getId()).map(p -> p.photoUrl(96)).orElse(null);
        return new Home(user, ship, photoUrl, toRead, certificates);
    }
}
