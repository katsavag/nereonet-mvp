package com.nereonix.nereonet_mvp.core.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

// Dev profile only: creates one demo ship if there are no ships yet, and two approved item requests
// (with a demo Master, Chief cook and items when the ship has none) if there are no requests yet,
// and two library items on that ship (one of them required) if its library is empty. P31: the demo Master and Chief
// cook get their ranks if they have none, and three more crew members (two with a profile) are on board, so My crew
// shows its departments.
@Component
@Profile("dev")
public class DevData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevData.class);

    private static final String DEMO_CREW_PASSWORD = "crew123";

    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ItemRepository itemRepository;
    private final ItemRequestRepository requestRepository;
    private final RequestLineRepository lineRepository;
    private final LibraryItemRepository libraryItemRepository;
    private final LibraryItemVersionRepository libraryVersionRepository;
    private final CrewProfileRepository profileRepository;

    public DevData(ShipRepository shipRepository, UserRepository userRepository, UserService userService,
                   ItemRepository itemRepository, ItemRequestRepository requestRepository,
                   RequestLineRepository lineRepository, LibraryItemRepository libraryItemRepository,
                   LibraryItemVersionRepository libraryVersionRepository, CrewProfileRepository profileRepository) {
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.itemRepository = itemRepository;
        this.requestRepository = requestRepository;
        this.lineRepository = lineRepository;
        this.libraryItemRepository = libraryItemRepository;
        this.libraryVersionRepository = libraryVersionRepository;
        this.profileRepository = profileRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (shipRepository.count() == 0) {
            Ship ship = new Ship();
            ship.setImoNumber("9074729");
            ship.setName("MV Demo");
            ship.setCreatedAt(Instant.now());
            shipRepository.save(ship);
            log.info("Created demo ship {}", ship.getName());
        }
        if (requestRepository.count() == 0) {
            createApprovedRequests(shipRepository.findAllByOrderByNameAsc().getFirst());
        }
        Ship firstShip = shipRepository.findAllByOrderByNameAsc().getFirst();
        if (!libraryItemRepository.existsByShip(firstShip)) {
            libraryItem(firstShip, "Galley hygiene rules", LibraryCategory.SHIP_RULES, false, "1",
                    "1. Wash your hands before entering the galley.\n"
                            + "2. Keep raw and cooked food apart.\n"
                            + "3. Clean all surfaces after each meal.");
            libraryItem(firstShip, "Drug and alcohol policy", LibraryCategory.COMPANY_POLICIES, true, "2026-10",
                    "Alcohol and drugs are not allowed on board.\n\n"
                            + "Every crew member must read and acknowledge this policy.");
            log.info("Created 2 demo library items for ship {}", firstShip.getName());
        }
        demoCrew(firstShip);
    }

    // P31: ranks for the demo Master and Chief cook (created without one before), and three crew members, each once.
    private void demoCrew(Ship ship) {
        demoRank("master.demo@example.com", Rank.MASTER);
        demoRank("cook.demo@example.com", Rank.CHIEF_COOK);
        crewMember(ship, "engineer.demo@example.com", "Nikos Pappas", Rank.CHIEF_ENGINEER, List.of("el", "en"), "GR",
                "Twenty years at sea, the last ten on bulk carriers.\nAsk me about the purifiers.");
        crewMember(ship, "ab.demo@example.com", "Maria Santos", Rank.ABLE_SEAMAN, List.of("tl", "en", "es"), "PH",
                null);
        crewMember(ship, "steward.demo@example.com", "Arjun Mehta", Rank.STEWARD, List.of(), null, null);
    }

    private void demoRank(String email, Rank rank) {
        userRepository.findByEmail(email).filter(user -> user.getRank() == null).ifPresent(user -> {
            user.setRank(rank);
            log.info("Gave demo user {} the rank {}", email, rank);
        });
    }

    // A crew member (password DEMO_CREW_PASSWORD) and, when anything is given, their profile.
    private void crewMember(Ship ship, String email, String fullName, Rank rank, List<String> languages,
                            String nationality, String bio) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User user = userService.createUser(email, fullName, DEMO_CREW_PASSWORD, rank.role(), ship, rank);
        if (!languages.isEmpty() || nationality != null || bio != null) {
            CrewProfile profile = new CrewProfile(user);
            profile.setLanguages(languages);
            profile.setNationality(nationality);
            profile.setBio(bio);
            profileRepository.save(profile);
        }
        log.info("Created demo user {} ({})", email, rank);
    }

    // A library item with one text version.
    private void libraryItem(Ship ship, String title, LibraryCategory category, boolean required,
                             String versionLabel, String text) {
        Instant now = Instant.now();
        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle(title);
        item.setCategory(category);
        item.setRequired(required);
        item.setCreatedAt(now);
        libraryItemRepository.save(item);

        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel(versionLabel);
        version.setText(text);
        version.setPublishedAt(now);
        item.setCurrentVersion(libraryVersionRepository.save(version));
    }

    private void createApprovedRequests(Ship ship) {
        User master = crewUser(ship, Role.MASTER, "master.demo@example.com", "Demo Master");
        User cook = crewUser(ship, Role.CHIEF_COOK, "cook.demo@example.com", "Demo Cook");
        Item rice = item(ship, "Basmati rice", "kg", "Dry store");
        Item oil = item(ship, "Olive oil", "lt", "Dry store");
        Item eggs = item(ship, "Eggs", "pcs", "Cold room");

        Instant now = Instant.now();
        ItemRequest first = request(ship, cook, master, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));
        line(first, rice, "25");
        line(first, oil, "10.5");
        line(first, eggs, "120");
        ItemRequest second = request(ship, cook, master, now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(1)));
        line(second, rice, "5");
        line(second, eggs, "60");
        log.info("Created 2 approved demo requests for ship {}", ship.getName());
    }

    // The ship's active user with this role, or a demo user (password DEMO_CREW_PASSWORD).
    private User crewUser(Ship ship, Role role, String email, String fullName) {
        return userRepository.findAllByShipAndRoleAndEnabledTrue(ship, role).stream().findFirst()
                .or(() -> userRepository.findByEmail(email))
                .orElseGet(() -> {
                    User user = userService.createUser(email, fullName, DEMO_CREW_PASSWORD, role, ship, null);
                    log.info("Created demo user {} ({})", email, role);
                    return user;
                });
    }

    // The ship's item with this name, or a new provisions item stored in this place.
    private Item item(Ship ship, String name, String unit, String place) {
        return itemRepository.findAllByShipIdOrderByNameAsc(ship.getId()).stream()
                .filter(item -> item.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    Item item = new Item();
                    item.setShip(ship);
                    item.setName(name);
                    item.setUnit(unit);
                    item.setCategory(ItemCategory.PROVISIONS);
                    item.setPlace(place);
                    item.setCreatedAt(Instant.now());
                    return itemRepository.save(item);
                });
    }

    private ItemRequest request(Ship ship, User requestedBy, User approvedBy, Instant createdAt, Instant approvedAt) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(requestedBy);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(approvedBy);
        request.setDecidedAt(approvedAt);
        request.setCreatedAt(createdAt);
        return requestRepository.save(request);
    }

    private void line(ItemRequest request, Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setRequest(request);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        request.getLines().add(lineRepository.save(line));
    }
}
