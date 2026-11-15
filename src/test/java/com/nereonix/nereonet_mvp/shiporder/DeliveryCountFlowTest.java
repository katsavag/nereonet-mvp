package com.nereonix.nereonet_mvp.shiporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountStatus;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFile;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFileRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovement;
import com.nereonix.nereonet_mvp.core.stock.StockMovementRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P61, the Chief cook counts the delivery and the Master approves it: Count delivery while CONFIRMED, a draft kept on
 * the order, All arrived, photos of the delivery note staged for the Master, Send to Master (the Master notified, the
 * cards say "Counted by the Chief cook, to approve"), Send back to cook with a note, the receipt prefilled with the
 * count and the differences on the review, then Confirm receipt approves it ("counted by" on the receipt, the RECEIPT
 * movements and the office's order page). Count by voice fills the lines without saving. Who may do what. Signed in
 * as the office unless a request says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class DeliveryCountFlowTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired OrderReceiptFileRepository fileRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired StockMovementRepository stockMovementRepository;

    Ship ship;
    User master;
    User cook;
    ShipOrder order;
    ShipOrderLine tomatoLine;
    ShipOrderLine eggLine;
    ShipOrderLine gelLine;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Rank.CHIEF_COOK.role(), ship,
                Rank.CHIEF_COOK);
        Item tomatoes = item("Tomatoes", "kg", ItemCategory.PROVISIONS);
        Item eggs = item("Eggs", "pcs", ItemCategory.PROVISIONS);
        Item gel = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);

        // Sent yesterday, delivery scheduled: 25 kg tomatoes, 30 eggs, 6 shower gels (the form's order: by category,
        // then name: Eggs, Tomatoes, Shower gel).
        order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.CONFIRMED);
        order.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        order.setSentBy(master);
        order.setSentAt(Instant.now().minus(1, ChronoUnit.DAYS));
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        order.setConfirmedPort("Antwerp");
        order.setDeliveryDate(LocalDate.now(ZoneOffset.UTC));
        orderRepository.save(order);
        tomatoLine = line(tomatoes, "25");
        eggLine = line(eggs, "30");
        gelLine = line(gel, "6");
    }

    @Test
    void theCookCountsTheMasterSendsItBackThenApprovesIt() throws Exception {
        long id = order.getId();
        // Count delivery: on the cook's Orders in progress and the order page; never for the Master.
        assertThat(as(cook, get("/ship/order"))).contains("href=\"/ship/orders/" + id + "/count\"", "Count delivery");
        assertThat(as(cook, get("/ship/orders/{id}", id))).contains("href=\"/ship/orders/" + id + "/count\"",
                "When the delivery arrives, count it");
        assertThat(as(master, get("/ship/order"))).doesNotContain("Count delivery").contains("Confirm receipt");

        // The count page: every line by category, Received empty, All arrived, the note and the photos.
        String page = as(cook, get("/ship/orders/{id}/count", id));
        assertThat(page).containsSubsequence("Provisions", "Eggs", "Ordered 30 pcs", "Tomatoes", "Ordered 25 kg",
                        "Personal items", "Shower gel", "Ordered 6 pcs")
                .contains("name=\"lines[0].counted\"", "All arrived", "name=\"allArrived\"", "Note for the Master",
                        "Photos of the delivery note", "type=\"file\"", "Send to Master", "Save draft",
                        "action=\"/ship/orders/" + id + "/count/voice\"")
                .doesNotContain("value=\"30\"");

        // Save draft: eggs not counted yet, tomatoes 20 with a note, the gel 6; nothing in the store.
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "", "", "20", "2 boxes damaged", "6", "",
                        "Meat comes tomorrow"))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count"))
                .andExpect(flash().attribute("successMessage", "order.count.saved"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.DRAFT);
        assertThat(order.getCountedBy().getId()).isEqualTo(cook.getId());
        assertThat(order.getCountNote()).isEqualTo("Meat comes tomorrow");
        assertThat(eggLine.getCountedQuantity()).isNull();
        assertThat(tomatoLine.getCountedQuantity()).isEqualByComparingTo("20");
        assertThat(tomatoLine.getCountNote()).isEqualTo("2 boxes damaged");
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);
        assertThat(movements()).isEmpty();
        // He comes back to it: the draft from the order.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).contains("value=\"20\"", "2 boxes damaged",
                "Meat comes tomorrow");

        // Send with the eggs not counted: the error under the line, nothing sent.
        String errors = as(cook, count(post("/ship/orders/{id}/count/send", id), "", "", "20", "2 boxes damaged", "6",
                "", "Meat comes tomorrow"));
        assertThat(errors).contains("Enter what arrived of Eggs: 0 if nothing arrived.");
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.DRAFT);
        // A quantity against the rule (P60): pcs are whole numbers.
        assertThat(as(cook, count(post("/ship/orders/{id}/count", id), "2.5", "", "20", "", "6", "", "")))
                .contains("Use whole numbers for pcs.");
        assertThat(eggLine.getCountedQuantity()).isNull();

        // All arrived without JavaScript: the eggs as ordered, saved.
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "", "", "20", "2 boxes damaged", "6", "",
                        "Meat comes tomorrow").param("allArrived", eggLine.getId().toString()))
                .andExpect(flash().attribute("successMessage", "order.count.saved"));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");

        // A photo of the delivery note: staged for the Master, uploaded by the cook.
        mockMvc.perform(multipart("/ship/orders/{id}/count/files", id)
                        .file(new MockMultipartFile("file", "note.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isNoContent());
        List<OrderReceiptFile> files = fileRepository.findAllByOrderIdOrderByIdAsc(id);
        assertThat(files).singleElement().satisfies(f -> assertThat(f.getUploadedBy().getId()).isEqualTo(cook.getId()));
        mockMvc.perform(get("/ship/orders/{id}/count/files/{f}", id, files.getFirst().getId())
                        .with(user(new CurrentUser(cook))))
                .andExpect(status().isOk());

        // Send to Master: the Master is notified, linking to the receipt.
        mockMvc.perform(count(post("/ship/orders/{id}/count/send", id), "30", "", "20", "2 boxes damaged", "6", "",
                        "Meat comes tomorrow"))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);
        assertThat(order.getCountSentAt()).isNotNull();
        assertThat(audit("ORDER_DELIVERY_COUNTED")).singleElement().satisfies(e -> {
            assertThat(e.getDetails()).isEqualTo("Order #" + id + ", 3 lines counted, 1 not as ordered");
            assertThat(e.getActor().getId()).isEqualTo(cook.getId());
        });
        assertThat(notifications("master@example.com")).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("Order " + id + ": delivery counted by Demo Cook, waiting for your approval");
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + id + "/receipt");
        });
        assertThat(movements()).isEmpty();

        // Sent: read only for the cook; another save or photo changes nothing.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).contains("Sent to the Master on",
                "waiting for approval", "Ordered 25 kg · Received 20 kg").doesNotContain("Save draft");
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "1", "", "1", "", "1", "", ""))
                .andExpect(flash().attribute("errorMessage", "order.count.alreadySent"));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");
        mockMvc.perform(multipart("/ship/orders/{id}/count/files", id)
                        .file(new MockMultipartFile("file", "late.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(as(cook, get("/ship/order"))).contains("Counted by the Chief cook, to approve", "View count");

        // The Master: the card, the home page and the receipt prefilled with the count.
        assertThat(as(master, get("/ship/order"))).contains("Counted by the Chief cook, to approve");
        assertThat(as(master, get("/ship"))).contains("Deliveries to receive", "Counted by the Chief cook, to approve");
        String receipt = as(master, get("/ship/orders/{id}/receipt", id));
        assertThat(receipt).contains("Counted by the Chief cook", "Demo Cook sent the count on", "Meat comes tomorrow",
                        "Send back to cook", "from the Chief cook", "note.jpg")
                .containsSubsequence("Eggs", "value=\"30\"", "Counted: 30 pcs", "Tomatoes", "value=\"20\"",
                        "Counted: 20 kg", "2 boxes damaged");

        // Send back to cook: a note is required.
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", " ")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "order.count.return.noteRequired"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "Recount the frozen meat")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("successMessage", "order.count.returned"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.RETURNED);
        assertThat(audit("ORDER_DELIVERY_COUNT_RETURNED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).isEqualTo("Order #" + id + ": Recount the frozen meat"));
        assertThat(notifications("cook@example.com")).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("Order " + id + ": the Master sent the delivery count back: Recount the frozen meat");
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + id + "/count");
        });
        // A second send back: nothing is waiting any more.
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "Again")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "order.count.return.nothing"));
        // The receipt now opens as ordered again (no count to approve).
        assertThat(as(master, get("/ship/orders/{id}/receipt", id))).doesNotContain("Counted: 20 kg")
                .contains("value=\"25\"");

        // The cook: the Master's note on top, his numbers kept; he sends again.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).containsSubsequence("The Master sent the count back on",
                "Recount the frozen meat", "value=\"30\"", "value=\"20\"");
        assertThat(as(master, get("/ship/order"))).contains("Count sent back to the Chief cook");
        mockMvc.perform(count(post("/ship/orders/{id}/count/send", id), "30", "", "22", "2 boxes damaged", "6", "",
                        "Meat comes tomorrow"))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);

        // Review: the Master changes the tomatoes to 21; the differences against the order and the count.
        String when = INPUT.format(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        String review = as(master, receipt(post("/ship/orders/{id}/receipt", id), "30", "21", "6", when));
        assertThat(review).contains("Differences", "Against the order", "Tomatoes: ordered 25 kg, received 21 kg",
                "Against the Chief cook&#39;s count", "Tomatoes: counted 22 kg, you entered 21 kg", "Changed from 22",
                ">Counted<");
        assertThat(review).doesNotContain("Eggs: ordered");

        // Confirm receipt: closed, the count approved, the cook is "counted by" everywhere.
        mockMvc.perform(receipt(post("/ship/orders/{id}/receipt/confirm", id), "30", "21", "6", when))
                .andExpect(flash().attribute("successMessage", "order.receipt.confirmed"));
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.APPROVED);
        assertThat(order.getReceivedBy().getId()).isEqualTo(master.getId());
        assertThat(order.getCountedBy().getId()).isEqualTo(cook.getId());
        assertThat(tomatoLine.getReceivedQuantity()).isEqualByComparingTo("21");
        assertThat(tomatoLine.getCountedQuantity()).isEqualByComparingTo("22");
        List<StockMovement> movements = movements();
        assertThat(movements).hasSize(2).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(StockMovementType.RECEIPT);
            assertThat(m.getNote()).isEqualTo("Counted by Demo Cook");
            assertThat(m.getBy().getId()).isEqualTo(master.getId());
        });
        assertThat(audit("ORDER_RECEIVED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).endsWith(", counted by Demo Cook"));
        assertThat(notifications("cook@example.com").getFirst().getText())
                .isEqualTo("Order " + id + " received: stock updated for 2 items");

        // The office's order page and the ship's: who counted it, and when.
        assertThat(page("/office/orders/" + id)).contains("Delivery count", "Counted by Demo Cook on");
        assertThat(as(master, get("/ship/orders/{id}", id))).contains("By Demo Cook on");

        // Counting is refused once received.
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(cook))))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("errorMessage", "order.count.received"));
        mockMvc.perform(count(post("/ship/orders/{id}/count/send", id), "1", "", "1", "", "1", "", ""))
                .andExpect(flash().attribute("errorMessage", "order.count.received"));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");
    }

    @Test
    void theMasterConfirmsWithoutACountAndTheOfficeMayStillChangeTheDelivery() throws Exception {
        long id = order.getId();
        // A draft only: the office moves the delivery, the count stays.
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "30", "", "", "", "", "", ""))
                .andExpect(flash().attribute("successMessage", "order.count.saved"));
        mockMvc.perform(post("/office/orders/{id}/confirm", id).param("confirmedPort", "Hamburg")
                        .param("deliveryDate", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(order.getConfirmedPort()).isEqualTo("Hamburg");
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.DRAFT);
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");

        // The Master confirms without the cook's count: as before P61, no "counted by".
        assertThat(as(master, get("/ship/orders/{id}/receipt", id))).doesNotContain("Counted by the Chief cook")
                .contains("value=\"25\"");
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", id)
                        .file(new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        // The cook removes only his own photos: the Master's file stays.
        Long masterFile = fileRepository.findAllByOrderIdOrderByIdAsc(id).getFirst().getId();
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "30", "", "", "", "", "", "")
                        .param("removeFileIds", masterFile.toString()))
                .andExpect(flash().attribute("successMessage", "order.count.saved"));
        assertThat(fileRepository.findAllByOrderIdOrderByIdAsc(id)).singleElement()
                .satisfies(f -> assertThat(f.getId()).isEqualTo(masterFile));
        mockMvc.perform(get("/ship/orders/{id}/count/files/{f}", id, masterFile).with(user(new CurrentUser(cook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(receipt(post("/ship/orders/{id}/receipt/confirm", id), "30", "25", "6",
                        INPUT.format(LocalDateTime.now(ZoneOffset.UTC))))
                .andExpect(flash().attribute("successMessage", "order.receipt.confirmed"));
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.DRAFT);
        assertThat(movements()).allSatisfy(m -> assertThat(m.getNote()).isNull());
        assertThat(audit("ORDER_RECEIVED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).doesNotContain("counted by"));
        assertThat(page("/office/orders/" + id)).doesNotContain("Counted by");
    }

    @Test
    void onlyTheVesselsChiefCookCountsAndOnlyItsMasterSendsBack() throws Exception {
        long id = order.getId();
        User crew = userService.createUser("crew@example.com", "Ana Santos", "secret123", Rank.ABLE_SEAMAN.role(), ship,
                Rank.ABLE_SEAMAN);
        Ship other = ship("9176187", "MV Other");
        User otherCook = userService.createUser("cook2@example.com", "Other Cook", "secret123",
                Rank.CHIEF_COOK.role(), other, Rank.CHIEF_COOK);
        User otherMaster = userService.createUser("master2@example.com", "Other Master", "secret123",
                Rank.MASTER.role(), other, Rank.MASTER);

        for (User user : List.of(master, crew)) {
            mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(user))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/ship/orders/{id}/count/send", id).with(user(new CurrentUser(user))).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/ship/orders/{id}/count", id)).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(otherCook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(count(post("/ship/orders/{id}/count", id), "30", "", "25", "", "6", "", "")
                        .with(user(new CurrentUser(otherCook))))
                .andExpect(status().isNotFound());
        assertThat(eggLine.getCountedQuantity()).isNull();

        // Sent, then only the vessel's Master sends it back.
        mockMvc.perform(count(post("/ship/orders/{id}/count/send", id), "30", "", "25", "", "6", "", ""))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "No")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "No")
                        .with(user(new CurrentUser(otherMaster))).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "No").with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);

        // Not scheduled yet: nothing to count.
        ShipOrder sent = new ShipOrder();
        sent.setShip(ship);
        sent.setStatus(ShipOrderStatus.SENT);
        sent.setCreatedAt(Instant.now());
        sent.setSentBy(master);
        sent.setSentAt(Instant.now());
        orderRepository.save(sent);
        mockMvc.perform(get("/ship/orders/{id}/count", sent.getId()).with(user(new CurrentUser(cook))))
                .andExpect(redirectedUrl("/ship/orders/" + sent.getId()))
                .andExpect(flash().attribute("errorMessage", "order.count.notConfirmed"));
        assertThat(as(cook, get("/ship/order"))).doesNotContain("href=\"/ship/orders/" + sent.getId() + "/count\"");
    }

    @Test
    void countByVoiceFillsTheLinesAndSavesNothing() throws Exception {
        long id = order.getId();
        // FakeSpeechClient: "Five kilos of tomatoes, eight kilos of chicken, thirty eggs, three cans of herring."
        mockMvc.perform(multipart("/ship/orders/{id}/count/voice", id)
                        .file(new MockMultipartFile("audio", "count.webm", "audio/webm", new byte[] {1, 2, 3}))
                        .accept(MediaType.APPLICATION_JSON).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heard.lines.length()").value(2))
                .andExpect(jsonPath("$.heard.lines[?(@.lineId == " + tomatoLine.getId() + ")].quantity").value("5"))
                .andExpect(jsonPath("$.heard.lines[?(@.lineId == " + eggLine.getId() + ")].quantity").value("30"))
                .andExpect(jsonPath("$.heard.unmatched.length()").value(2))
                .andExpect(jsonPath("$.heard.summary").value("2 lines filled from what we heard. Check them, then save the draft."));
        assertThat(tomatoLine.getCountedQuantity()).isNull();
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.NONE);
        // No recording: said in plain words.
        mockMvc.perform(multipart("/ship/orders/{id}/count/voice", id).accept(MediaType.APPLICATION_JSON)
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("The recording did not arrive. Record it again."));
    }

    // ---------------------------------------------------------------------------------------------------------------

    // The count's fields, as the cook: lines in the form's order (eggs, tomatoes, then the gel), then his note.
    private <B extends AbstractMockHttpServletRequestBuilder<B>> B count(B request, String eggs, String eggNote,
            String tomatoes, String tomatoNote, String gel, String gelNote, String note) {
        countLine(request, 0, eggLine, eggs, eggNote);
        countLine(request, 1, tomatoLine, tomatoes, tomatoNote);
        countLine(request, 2, gelLine, gel, gelNote);
        return request.param("note", note).with(user(new CurrentUser(cook))).with(csrf());
    }

    private static void countLine(AbstractMockHttpServletRequestBuilder<?> request, int index, ShipOrderLine line,
                                  String counted, String note) {
        String p = "lines[" + index + "]";
        request.param(p + ".lineId", line.getId().toString()).param(p + ".counted", counted).param(p + ".note", note);
    }

    // The receipt form's fields, as the Master: eggs, tomatoes, gel (no notes), then the time.
    private <B extends AbstractMockHttpServletRequestBuilder<B>> B receipt(B request, String eggs, String tomatoes,
            String gel, String receivedAt) {
        String[][] lines = {{eggLine.getId().toString(), eggs}, {tomatoLine.getId().toString(), tomatoes},
                {gelLine.getId().toString(), gel}};
        for (int i = 0; i < lines.length; i++) {
            request.param("lines[" + i + "].lineId", lines[i][0]).param("lines[" + i + "].received", lines[i][1])
                    .param("lines[" + i + "].note", "");
        }
        return request.param("receivedAt", receivedAt).with(user(new CurrentUser(master))).with(csrf());
    }

    private String as(User user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(user)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Notification> notifications(String email) {
        return notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(email);
    }

    private List<StockMovement> movements() {
        return stockMovementRepository.findOnShip(ship.getId(), List.of(StockMovementType.values()), Instant.EPOCH,
                Instant.now().plus(1, ChronoUnit.DAYS), Pageable.unpaged()).getContent();
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }

    private Ship ship(String imo, String name) {
        Ship created = new Ship();
        created.setImoNumber(imo);
        created.setName(name);
        created.setCreatedAt(Instant.now());
        return shipRepository.save(created);
    }

    private ShipOrderLine line(Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        line.setOriginalQuantity(new BigDecimal(quantity));
        order.getLines().add(lineRepository.save(line));
        return line;
    }

    private Item item(String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setCreatedAt(Instant.now());
        item.setActive(true);
        return itemRepository.save(item);
    }
}
