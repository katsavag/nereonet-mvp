package com.nereonix.nereonet_mvp.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.supplier.ChannelType;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.core.vetting.GhgRating;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.SireInspection;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireStatus;
import com.nereonix.nereonet_mvp.core.vetting.Subscore;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

// Dev profile only: creates one demo ship if there are no ships yet, a demo Master on the first ship while there are
// no requests and it has none, and two library items on that ship (one of them required) if its library is empty. P31: the demo
// Master and Chief cook get their ranks if they have none, and three more crew members (two with a profile) are on
// board, so My crew shows its departments. P33: while that ship has no PENDING request, two waiting for its Master:
// one from the Chief cook (three provisions) and one from a crew member (a personal item, and eggs as in the cook's
// request). P36: no approved requests are seeded any more; the Master approves these and sends the ship order.
// P40: once, three bulk carriers with RightShip data and sample reports (ALDER, LAUREL, ROWAN; see vettingDemo).
// P41: once, the tanker BIRCH with a SIRE 2.0 inspection, its observations and sample report (see sireDemo).
@Component
@Profile("dev")
public class DevData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevData.class);

    private static final String DEMO_CREW_PASSWORD = "crew123";

    private static final String DEMO_SHIP_IMO = "9074729";
    // P40: ALDER, LAUREL and ROWAN, the RightShip demo vessels.
    private static final List<String> VETTING_IMOS = List.of("9300001", "9400007", "9500003");
    private static final LocalDate RIGHTSHIP_DATE = LocalDate.of(2026, 10, 21);
    // P41: BIRCH, the SIRE 2.0 demo tanker, and the dates of its synthetic sample report.
    private static final String SIRE_IMO = "9600009";
    private static final LocalDate SIRE_INSPECTION_DATE = LocalDate.of(2026, 6, 12);
    private static final LocalDate SIRE_PUBLISHED_ON = LocalDate.of(2026, 6, 26);

    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ItemRepository itemRepository;
    private final ItemRequestRepository requestRepository;
    private final LibraryItemRepository libraryItemRepository;
    private final LibraryItemVersionRepository libraryVersionRepository;
    private final CrewProfileRepository profileRepository;
    private final RequestService requestService;
    private final VettingService vettingService;
    private final SireService sireService;
    private final SupplierRepository supplierRepository;

    public DevData(ShipRepository shipRepository, UserRepository userRepository, UserService userService,
                   ItemRepository itemRepository, ItemRequestRepository requestRepository,
                   LibraryItemRepository libraryItemRepository, LibraryItemVersionRepository libraryVersionRepository,
                   CrewProfileRepository profileRepository, RequestService requestService,
                   VettingService vettingService, SireService sireService, SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
        this.vettingService = vettingService;
        this.sireService = sireService;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.itemRepository = itemRepository;
        this.requestRepository = requestRepository;
        this.libraryItemRepository = libraryItemRepository;
        this.libraryVersionRepository = libraryVersionRepository;
        this.profileRepository = profileRepository;
        this.requestService = requestService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (shipRepository.count() == 0) {
            Ship ship = new Ship();
            ship.setImoNumber("9074729");
            ship.setName("MV Demo");
            ship.setFlag("GR"); // P43: the flag icon in the lists and headers
            ship.setCreatedAt(Instant.now());
            shipRepository.save(ship);
            log.info("Created demo ship {}", ship.getName());
        }
        // The demo ship by its IMO number: the RightShip demo vessels (P40) sort before it by name.
        Ship firstShip = shipRepository.findByImoNumber(DEMO_SHIP_IMO).orElseGet(() -> shipRepository
                .findAllByOrderByNameAsc().stream()
                .filter(s -> !VETTING_IMOS.contains(s.getImoNumber()) && !SIRE_IMO.equals(s.getImoNumber())).findFirst()
                .orElseGet(() -> shipRepository.findAllByOrderByNameAsc().getFirst()));
        if (requestRepository.count() == 0) {
            // A fresh database: the Master who approves the seeded requests (the Chief cook comes with them).
            crewUser(firstShip, Role.MASTER, "master.demo@example.com", "Demo Master");
        }
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
        if (requestRepository.countByStatusAndShipId(RequestStatus.PENDING, firstShip.getId()) == 0) {
            createPendingRequests(firstShip);
        }
        if (!shipRepository.existsByImoNumber(VETTING_IMOS.getFirst())) {
            vettingDemo();
        }
        if (!shipRepository.existsByImoNumber(SIRE_IMO)) {
            sireDemo();
        }
        if (supplierRepository.count() == 0) {
            suppliers();
        }
    }

    // P44: two suppliers to ask for quotes. VANOS is a real ship chandler in Piraeus: its name and website only, with
    // a test address (example.com never delivers), so a dev RFQ never reaches it. The second is made up, in USD, to
    // show a quote in another currency listed apart from the comparison.
    private void suppliers() {
        supplier("VANOS (Piraeus)", "quotes.vanos@example.com", "Piraeus, Perama, Elefsina", "EUR",
                "https://www.vanos.gr/");
        supplier("Harbour Provisions (Test)", "sales.harbour@example.com", "Piraeus, Rotterdam, Antwerp", "USD", null);
        log.info("Created 2 demo suppliers");
    }

    private void supplier(String name, String email, String ports, String currency, String website) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail(email);
        supplier.setPortsServed(ports);
        supplier.setCurrency(currency);
        supplier.setWebsite(website);
        supplier.setActive(true);
        supplier.setChannel(ChannelType.EMAIL);
        supplier.setCreatedAt(Instant.now());
        supplierRepository.save(supplier);
    }

    // P41: the tanker BIRCH with the SIRE 2.0 inspection of its synthetic sample report (samples/sire, read from the
    // working directory; "Sample report · Synthetic vessel data", no real company): Piraeus, 12 Jun 2026, published
    // 26 Jun 2026, and its five observations, two Hardware, two Process, one Human. Three are closed, one is open
    // (target in 30 days) and one is overdue (target 12 days ago), so BIRCH counts in the sidebar and the Overview
    // lists the overdue one.
    private void sireDemo() {
        Ship ship = new Ship();
        ship.setImoNumber(SIRE_IMO);
        ship.setName("BIRCH");
        ship.setType(ShipType.TANKER);
        ship.setFlag("LR"); // P43: the flag icon in the lists and headers
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        SireInspection values = new SireInspection();
        values.setInspectionDate(SIRE_INSPECTION_DATE);
        values.setPort("Piraeus, Greece");
        values.setSubmittingCompany("Demo Energy Ltd (fictional)");
        values.setReportReference("DEMO-SIRE-0001");
        values.setStatus(SireStatus.PUBLISHED);
        values.setPublishedOn(SIRE_PUBLISHED_ON);
        SireInspection inspection = sireService.addInspection(ship, values, null);

        User owner = userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER).stream().findFirst().orElse(null);
        LocalDate today = VettingService.today();
        sireObservation(inspection, 5, "5.4.1", ObservationType.HARDWARE,
                "Bridge navigation light indicator panel lamp failed.", "Lamp replaced on the day of the inspection.",
                "Spare lamps added to the bridge spares list.", owner, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 6, 20));
        sireObservation(inspection, 8, "8.2.3", ObservationType.PROCESS,
                "Cargo transfer checklist not fully signed for last discharge.", "Checklist completed and signed.",
                "Cargo officers briefed on the checklist sign-off.", owner, LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 24));
        sireObservation(inspection, 9, "9.1.2", ObservationType.HARDWARE,
                "Fire main isolating valve hard to operate on deck.", "Valve to be overhauled at the next port call.",
                "Overhaul the valve and add it to the planned maintenance.", owner, today.minusDays(12), null);
        sireObservation(inspection, 4, "4.1.6", ObservationType.PROCESS,
                "Familiarisation record missing for one rating.", "Record completed on board.",
                "Familiarisation checklist added to the joining procedure.", owner, LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 14));
        sireObservation(inspection, 7, "7.3.1", ObservationType.HUMAN,
                "Officer unsure of emergency stop location for cargo pumps.", null,
                "Emergency stop drill for all cargo officers.", owner, today.plusDays(30), null);

        Path file = Path.of("samples", "sire", "birch-sire-sample.pdf");
        if (!Files.isRegularFile(file)) {
            log.warn("No sample SIRE report {}: BIRCH gets none (run from the project folder)", file);
        } else {
            try (InputStream in = Files.newInputStream(file)) {
                sireService.saveReport(inspection, in, file.getFileName().toString(), Files.size(file), null);
            } catch (IOException e) {
                log.warn("Could not read the sample SIRE report {}", file, e);
            }
        }
        log.info("Created SIRE 2.0 demo vessel BIRCH");
    }

    private void sireObservation(SireInspection inspection, int chapter, String question, ObservationType type,
                                 String description, String comment, String action, User owner, LocalDate target,
                                 LocalDate closedOn) {
        SireObservation values = new SireObservation();
        values.setChapter(chapter);
        values.setQuestionNumber(question);
        values.setType(type);
        values.setDescription(description);
        values.setOperatorComment(comment);
        values.setCorrectiveAction(action);
        values.setOwner(owner);
        values.setTargetDate(target);
        values.setClosedOn(closedOn);
        sireService.addObservation(inspection, values, null);
    }

    // P40: three bulk carriers with RightShip data (entered by hand, calculated 21 Oct 2026) and each its synthetic
    // sample report (samples/rightship, read from the working directory). ALDER's inspection expires in 38 days, so
    // the sidebar shows "Vetting 1" and the Overview lists it; the demo ship stays without RightShip data.
    private void vettingDemo() {
        LocalDate today = VettingService.today();
        vettingVessel("ALDER", VETTING_IMOS.get(0), 3, List.of(4, 2, 3, 4, 4, 5), GhgRating.C, false, false,
                today.plusDays(38), "alder-3-of-5.png");
        vettingVessel("LAUREL", VETTING_IMOS.get(1), 5, List.of(5, 5, 4, 5, 5, 5), GhgRating.A, true, true,
                today.plusMonths(9), "laurel-5-of-5.png");
        vettingVessel("ROWAN", VETTING_IMOS.get(2), 4, List.of(4, 4, 3, 5, 4, 5), GhgRating.B, false, true,
                today.plusMonths(6), "rowan-4-of-5.png");
    }

    private void vettingVessel(String name, String imo, int score, List<Integer> subscores, GhgRating ghg,
                               boolean ghgPlus, boolean ghgVerified, LocalDate inspectionValidUntil, String report) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setType(ShipType.BULK_CARRIER);
        ship.setFlag("MT"); // P43: the flag icon in the lists and headers
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        ShipRightShipStatus values = new ShipRightShipStatus();
        values.setSafetyScore(score);
        values.setCalculatedAt(RIGHTSHIP_DATE);
        Subscore[] all = Subscore.values();
        for (int i = 0; i < all.length; i++) {
            values.setSubscore(all[i], subscores.get(i));
        }
        values.setGhgRating(ghg);
        values.setGhgPlus(ghgPlus);
        values.setGhgVerified(ghgVerified);
        values.setGhgRatedAt(RIGHTSHIP_DATE);
        values.setInspectionRequired(true);
        values.setLastInspectionOutcome(InspectionOutcome.ACCEPTABLE);
        values.setInspectionValidUntil(inspectionValidUntil);
        vettingService.saveManual(ship, values, null);

        Path file = Path.of("samples", "rightship", report);
        if (!Files.isRegularFile(file)) {
            log.warn("No sample RightShip report {}: {} gets none (run from the project folder)", file, name);
        } else {
            try (InputStream in = Files.newInputStream(file)) {
                vettingService.saveReport(ship, in, report, "image/png", Files.size(file), RIGHTSHIP_DATE, null);
            } catch (IOException e) {
                log.warn("Could not read the sample RightShip report {}", file, e);
            }
        }
        log.info("Created RightShip demo vessel {}", name);
    }

    // P33: submitted as the Chief cook and the crew would, so the Master also gets the two notifications.
    private void createPendingRequests(Ship ship) {
        User cook = crewUser(ship, Role.CHIEF_COOK, "cook.demo@example.com", "Demo Cook");
        User crewMember = userRepository.findByEmail("ab.demo@example.com").orElseThrow();
        Item rice = item(ship, "Basmati rice", "kg", ItemCategory.PROVISIONS, "Dry store");
        Item oil = item(ship, "Olive oil", "lt", ItemCategory.PROVISIONS, "Dry store");
        Item eggs = item(ship, "Eggs", "pcs", ItemCategory.PROVISIONS, "Cold room");
        Item showerGel = item(ship, "Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS, null);

        ItemRequest fromCook = pendingRequest(ship, cook, Instant.now().minus(Duration.ofHours(20)));
        fromCook.getLines().add(pendingLine(rice, "40"));
        fromCook.getLines().add(pendingLine(oil, "12"));
        fromCook.getLines().add(pendingLine(eggs, "180"));
        requestService.submit(fromCook);

        ItemRequest fromCrew = pendingRequest(ship, crewMember, Instant.now().minus(Duration.ofHours(2)));
        fromCrew.getLines().add(pendingLine(showerGel, "2"));
        fromCrew.getLines().add(pendingLine(eggs, "12"));
        requestService.submit(fromCrew);
        log.info("Created 2 pending demo requests for ship {}", ship.getName());
    }

    private static ItemRequest pendingRequest(Ship ship, User requestedBy, Instant createdAt) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(requestedBy);
        request.setCreatedAt(createdAt);
        return request;
    }

    private static RequestLine pendingLine(Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        return line;
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

    // The ship's item with this name, or a new item of this category (place: null for personal items).
    private Item item(Ship ship, String name, String unit, ItemCategory category, String place) {
        return itemRepository.findAllByShipIdOrderByNameAsc(ship.getId()).stream()
                .filter(item -> item.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    Item item = new Item();
                    item.setShip(ship);
                    item.setName(name);
                    item.setUnit(unit);
                    item.setCategory(category);
                    item.setPlace(place);
                    item.setCreatedAt(Instant.now());
                    return itemRepository.save(item);
                });
    }
}
