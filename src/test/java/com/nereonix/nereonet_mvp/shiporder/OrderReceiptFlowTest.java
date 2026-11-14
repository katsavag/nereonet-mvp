package com.nereonix.nereonet_mvp.shiporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
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
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P51, the Master confirms receipt on board with the delivery receipt: only for a CONFIRMED order (delivery
 * scheduled), the form prefilled with what was ordered, the receipt's files (PDF, JPEG or PNG; sent with the form or one
 * at a time by js/receipt.js), the review step, then CLOSED with what arrived; the office is notified and sees the
 * receipt, shortages stay in the Overview until "Shortage handled". Signed in as the office unless a request says
 * otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class OrderReceiptFlowTest {

    private static final byte[] PDF = "%PDF-1.7\n% a delivery note\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
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

    Ship ship;
    User master;
    User cook;
    Item rice;
    Item oil;
    Item soap;
    ShipOrder order;
    ShipOrderLine riceLine;
    ShipOrderLine oilLine;
    ShipOrderLine soapLine;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Rank.CHIEF_COOK.role(), ship,
                Rank.CHIEF_COOK);
        rice = item("Basmati rice", "kg", ItemCategory.PROVISIONS);
        oil = item("Olive oil", "l", ItemCategory.PROVISIONS);
        soap = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);

        // Sent yesterday, delivery scheduled by the office: 25 kg rice, 10 l oil, 6 shower gels.
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
        riceLine = line(rice, "25");
        oilLine = line(oil, "10");
        soapLine = line(soap, "6");
    }

    @Test
    void theMasterConfirmsReceiptWithTheDeliveryReceiptAndTheOfficeSeesIt() throws Exception {
        // The order page: Confirm receipt, the one primary action.
        assertThat(asMaster(get("/ship/orders/{id}", order.getId()))).contains("Delivery scheduled",
                "href=\"/ship/orders/" + order.getId() + "/receipt\">Confirm receipt</a>");

        // The form: every line, received prefilled with what was ordered; now (UTC) as the time.
        String form = asMaster(get("/ship/orders/{id}/receipt", order.getId()));
        assertThat(form).containsSubsequence("Basmati rice", "25 kg", "value=\"25\"", "Olive oil", "10 l", "value=\"10\"",
                        "Shower gel", "6 pcs", "value=\"6\"")
                .contains("name=\"lines[0].notDelivered\"", "type=\"file\"", "multiple", "name=\"receivedAt\"",
                        "type=\"datetime-local\"", "enctype=\"multipart/form-data\"",
                        "data-draft=\"/ship/orders/" + order.getId() + "/receipt\"");

        // Review, without JavaScript: the file goes with the form. 20 kg rice (short), no oil, 6 gels.
        String when = INPUT.format(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        String review = mockMvc.perform(receipt(multipart("/ship/orders/{id}/receipt", order.getId())
                                .file(new MockMultipartFile("files", "delivery-note.pdf", "application/pdf", PDF)),
                        "20", false, "Two bags torn", "", true, "", "6", false, "", when))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(review).contains("Review the receipt", "2 lines short", "Short by 5 kg", "Not delivered",
                        "Two bags torn", "delivery-note.pdf", "Confirm receipt", "Back to edit",
                        "data-draft-for=\"/ship/orders/" + order.getId() + "/receipt\"")
                .contains("name=\"lines[0].received\" value=\"20\"", "name=\"lines[1].received\" value=\"0\"");
        // Nothing saved yet but the file, which waits on the order (only the Master sees it).
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);
        List<OrderReceiptFile> files = fileRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        assertThat(files).singleElement().satisfies(f -> {
            assertThat(f.getFileName()).isEqualTo("delivery-note.pdf");
            assertThat(f.getFileContentType()).isEqualTo("application/pdf");
            assertThat(f.getUploadedBy().getId()).isEqualTo(master.getId());
        });
        mockMvc.perform(get("/office/orders/{id}/receipt/{f}", order.getId(), files.getFirst().getId()))
                .andExpect(status().isNotFound());
        assertThat(notifications("office@example.com")).isEmpty();

        // Confirm receipt (the review page's hidden fields): CLOSED.
        mockMvc.perform(receipt(post("/ship/orders/{id}/receipt/confirm", order.getId()),
                        "20", false, "Two bags torn", "0", true, "", "6", false, "", when))
                .andExpect(redirectedUrl("/ship/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "order.receipt.confirmed"));
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);
        assertThat(order.getReceivedBy().getId()).isEqualTo(master.getId());
        assertThat(order.getReceivedAt()).isEqualTo(LocalDateTime.parse(when).toInstant(ZoneOffset.UTC));
        assertThat(order.getShortLines()).isEqualTo(2);
        assertThat(riceLine.getReceivedQuantity()).isEqualByComparingTo("20");
        assertThat(riceLine.getReceiptNote()).isEqualTo("Two bags torn");
        assertThat(riceLine.getShortBy()).isEqualByComparingTo("5");
        assertThat(oilLine.getReceivedQuantity()).isEqualByComparingTo("0");
        assertThat(soapLine.getShortBy()).isNull();
        assertThat(audit("ORDER_RECEIVED")).singleElement().satisfies(e -> {
            assertThat(e.getDetails()).startsWith("Order #" + order.getId() + ", received ")
                    .endsWith(", 2 lines short, 1 receipt file");
            assertThat(e.getShip().getId()).isEqualTo(ship.getId());
        });
        assertThat(notifications("office@example.com")).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("MV Demo confirmed receipt of order " + order.getId() + " (2 lines short)");
            assertThat(n.getLink()).isEqualTo("/office/orders/" + order.getId());
        });
        // The Master's page: received, the file, what arrived; no more Confirm receipt.
        assertThat(asMaster(get("/ship/orders/{id}", order.getId()))).contains(">Received<", "by Ship Master",
                "delivery-note.pdf", "Short by 5 kg", "Not delivered").doesNotContain(">Confirm receipt</a>");

        // The office: Received, by whom, the file inline and as a download, ordered vs received.
        String office = page("/office/orders/" + order.getId());
        assertThat(office).contains("Receipt on board", "by Ship Master", "2 lines arrived short", "delivery-note.pdf",
                        "/office/orders/" + order.getId() + "/receipt/" + files.getFirst().getId(), ">Received</span>",
                        "Short by 5 kg", "Not delivered", "Two bags torn", "/shortage-handled\"")
                .doesNotContain("id=\"confirmTitle\"");
        mockMvc.perform(get("/office/orders/{id}/receipt/{f}", order.getId(), files.getFirst().getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(content().bytes(PDF));
        mockMvc.perform(get("/office/orders/{id}/receipt/{f}", order.getId(), files.getFirst().getId())
                        .param("download", "true"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        assertThat(page("/office/orders?status=closed")).contains("Order #" + order.getId() + "<", ">Received</span>");

        // The export: received_quantity and received_at.
        MvcResult export = mockMvc.perform(post("/office/orders/export").param("ids", order.getId().toString())
                .with(csrf())).andExpect(status().isOk()).andReturn();
        XlsSheet sheet = XlsSheet.of(export.getResponse().getContentAsByteArray());
        int status = sheet.header().indexOf("status");
        int received = sheet.header().indexOf("received_quantity");
        int receivedAt = sheet.header().indexOf("received_at");
        assertThat(sheet.body()).extracting(row -> row.get(status)).containsOnly("received");
        assertThat(sheet.body()).extracting(row -> row.get(received)).containsExactly(20.0, 0.0, 6.0);
        assertThat(sheet.body()).extracting(row -> row.get(receivedAt)).containsOnly(LocalDateTime.parse(when));

        // Again (another tab): nothing changes.
        mockMvc.perform(receipt(post("/ship/orders/{id}/receipt/confirm", order.getId()),
                        "25", false, "", "10", false, "", "6", false, "", when))
                .andExpect(redirectedUrl("/ship/orders/" + order.getId()))
                .andExpect(flash().attribute("errorMessage", "order.receipt.alreadyReceived"));
        assertThat(riceLine.getReceivedQuantity()).isEqualByComparingTo("20");
    }

    @Test
    void aShortageStaysInNeedsAttentionUntilTheOfficeMarksItHandled() throws Exception {
        receive("20", "10", "6");
        assertThat(page("/office")).contains("Order #" + order.getId() + " · received short", "1 line short");

        // A note is required.
        mockMvc.perform(post("/office/orders/{id}/shortage-handled", order.getId()).param("note", " ").with(csrf()))
                .andExpect(flash().attribute("errorMessage", "officeorder.shortage.noteRequired"));
        assertThat(order.getShortageHandledAt()).isNull();

        mockMvc.perform(post("/office/orders/{id}/shortage-handled", order.getId())
                        .param("note", "Supplier delivers the rest in Hamburg").with(csrf()))
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "officeorder.shortage.handled"));
        assertThat(order.getShortageHandledAt()).isNotNull();
        assertThat(order.getShortageHandledBy().getEmail()).isEqualTo("office@example.com");
        assertThat(audit("ORDER_SHORTAGE_HANDLED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", 1 line short: Supplier delivers the rest in Hamburg");
        assertThat(page("/office")).doesNotContain("Order #" + order.getId() + " · received short");
        assertThat(page("/office/orders/" + order.getId())).contains("Supplier delivers the rest in Hamburg")
                .doesNotContain("name=\"note\"");
        // Twice: nothing open any more.
        mockMvc.perform(post("/office/orders/{id}/shortage-handled", order.getId()).param("note", "Again").with(csrf()))
                .andExpect(flash().attribute("errorMessage", "officeorder.shortage.nothingOpen"));
    }

    @Test
    void everythingReceivedIsNoShortageAndTheOfficeIsToldSo() throws Exception {
        receive("25", "10", "6.000");
        assertThat(order.getShortLines()).isZero();
        assertThat(notifications("office@example.com")).extracting(Notification::getText)
                .containsExactly("MV Demo confirmed receipt of order " + order.getId());
        assertThat(page("/office/orders/" + order.getId())).contains("Everything arrived as ordered.")
                .doesNotContain("/shortage-handled\"");
        assertThat(page("/office")).doesNotContain("received short");
    }

    @Test
    void theFormComesBackWithErrorsAndKeepsTheFilesSent() throws Exception {
        String future = INPUT.format(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        // No file, a quantity that is not one, a note too long, a time in the future.
        String errors = mockMvc.perform(receipt(post("/ship/orders/{id}/receipt", order.getId()),
                        "lots", false, "x".repeat(201), "10", false, "", "6", false, "", future))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(errors).contains("Enter the quantity received: zero or more, with at most 3 decimals.",
                "The note is too long: at most 200 characters.", "The time received cannot be in the future.",
                "Add the delivery receipt: a PDF, JPEG or PNG file.");

        // A file of another kind is refused; a JPEG is kept although the form has another error.
        String before = INPUT.format(order.getSentAt().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC).toLocalDateTime());
        String again = mockMvc.perform(receipt(multipart("/ship/orders/{id}/receipt", order.getId())
                                .file(new MockMultipartFile("files", "notes.docx", "application/pdf", PDF))
                                .file(new MockMultipartFile("files", "photo.jpg", "image/jpeg", JPEG)),
                        "25", false, "", "10", false, "", "6", false, "", before))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(again).contains("notes.docx: only PDF, JPEG or PNG files can be added.",
                        "The time received cannot be before the order was sent.", "Already uploaded", "photo.jpg")
                .doesNotContain("Add the delivery receipt");
        List<OrderReceiptFile> files = fileRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        assertThat(files).extracting(OrderReceiptFile::getFileName).containsExactly("photo.jpg");

        // Ticking Remove takes it off; then the receipt is missing again.
        String removed = mockMvc.perform(receipt(post("/ship/orders/{id}/receipt", order.getId())
                                .param("removeFileIds", files.getFirst().getId().toString()),
                        "25", false, "", "10", false, "", "6", false, "", INPUT.format(LocalDateTime.now(ZoneOffset.UTC))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(removed).contains("Add the delivery receipt: a PDF, JPEG or PNG file.").doesNotContain("photo.jpg");
        assertThat(fileRepository.countByOrderId(order.getId())).isZero();
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);
    }

    @Test
    void jsSendsEachFileAloneFirst() throws Exception {
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", order.getId())
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", order.getId())
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("notes.txt: only PDF, JPEG or PNG files can be added."));
        assertThat(fileRepository.findAllByOrderIdOrderByIdAsc(order.getId())).extracting(OrderReceiptFile::getFileName)
                .containsExactly("photo.jpg");

        // The form then goes without files: the one sent counts.
        String review = mockMvc.perform(receipt(post("/ship/orders/{id}/receipt", order.getId()),
                        "25", false, "", "10", false, "", "6", false, "", INPUT.format(LocalDateTime.now(ZoneOffset.UTC))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(review).contains("Review the receipt", "photo.jpg", "Everything arrived as ordered.");

        // Too large for one request (UploadTooLargeFilter): back to the form, which says so.
        mockMvc.perform(get("/ship/orders/{id}/receipt/files", order.getId()).param("fileTooLarge", "")
                        .with(user(new CurrentUser(master))))
                .andExpect(redirectedUrl("/ship/orders/" + order.getId() + "/receipt?fileTooLarge"));
        assertThat(asMaster(get("/ship/orders/{id}/receipt", order.getId()).param("fileTooLarge", "")))
                .contains("The files were too large to send together.");
    }

    @Test
    void onlyTheMasterOfTheShipAndOnlyOnceTheDeliveryIsScheduled() throws Exception {
        // Not scheduled yet: back to the order with a message; no Confirm receipt.
        order.setStatus(ShipOrderStatus.SENT);
        mockMvc.perform(get("/ship/orders/{id}/receipt", order.getId()).with(user(new CurrentUser(master))))
                .andExpect(redirectedUrl("/ship/orders/" + order.getId()))
                .andExpect(flash().attribute("errorMessage", "order.receipt.notConfirmed"));
        assertThat(asMaster(get("/ship/orders/{id}", order.getId()))).doesNotContain(">Confirm receipt</a>");
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", order.getId())
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isConflict());
        order.setStatus(ShipOrderStatus.CONFIRMED);

        // The Chief cook has no order pages; another ship's Master gets a 404; the office cannot close an order.
        mockMvc.perform(get("/ship/orders/{id}/receipt", order.getId()).with(user(new CurrentUser(cook))))
                .andExpect(status().isForbidden());
        Ship other = new Ship();
        other.setImoNumber("9176187");
        other.setName("MV Other");
        other.setCreatedAt(Instant.now());
        shipRepository.save(other);
        User otherMaster = userService.createUser("master2@example.com", "Other Master", "secret123",
                Rank.MASTER.role(), other, Rank.MASTER);
        mockMvc.perform(get("/ship/orders/{id}/receipt", order.getId()).with(user(new CurrentUser(otherMaster))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ship/orders/{id}/receipt", order.getId())).andExpect(status().isForbidden());
        assertThat(page("/office/orders/" + order.getId())).contains(">Delivery pending</span>", "Change delivery")
                .doesNotContain("Receipt on board");
    }

    // ---------------------------------------------------------------------------------------------------------------

    // The Master receives the order with a PDF receipt: rice, oil and gel received as given.
    private void receive(String rice, String oil, String gel) throws Exception {
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", order.getId())
                        .file(new MockMultipartFile("file", "receipt.pdf", "application/pdf", PDF))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(receipt(post("/ship/orders/{id}/receipt/confirm", order.getId()), rice, false, "", oil, false,
                        "", gel, false, "", INPUT.format(LocalDateTime.now(ZoneOffset.UTC))))
                .andExpect(flash().attribute("successMessage", "order.receipt.confirmed"));
    }

    // The form's fields, as the Master: lines in the form's order (rice, oil, then the gel), then the time.
    private <B extends AbstractMockHttpServletRequestBuilder<B>> B receipt(B request, String riceReceived,
            boolean riceNone, String riceNote, String oilReceived, boolean oilNone, String oilNote, String gelReceived,
            boolean gelNone, String gelNote, String receivedAt) {
        line(request, 0, riceLine, riceReceived, riceNone, riceNote);
        line(request, 1, oilLine, oilReceived, oilNone, oilNote);
        line(request, 2, soapLine, gelReceived, gelNone, gelNote);
        return request.param("receivedAt", receivedAt).with(user(new CurrentUser(master))).with(csrf());
    }

    private static void line(AbstractMockHttpServletRequestBuilder<?> request, int index, ShipOrderLine line,
                             String received, boolean none, String note) {
        String p = "lines[" + index + "]";
        request.param(p + ".lineId", line.getId().toString()).param(p + ".received", received)
                .param(p + ".note", note);
        if (none) {
            request.param(p + ".notDelivered", "true");
        }
    }

    private String asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(master)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Notification> notifications(String email) {
        return notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(email);
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
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
