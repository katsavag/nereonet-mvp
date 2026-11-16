package com.nereonix.nereonet_mvp.shiporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ai.FakeInvoiceReader;
import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountStatus;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoice;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLine;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLineRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceStatus;
import com.nereonix.nereonet_mvp.core.order.InvoicePageStatus;
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

import tools.jackson.databind.JsonNode;

/**
 * Photographing the delivery invoice and reading it: Count delivery (the cook) and Confirm receipt (the Master,
 * without a count to approve) open "Photograph the invoice" first; the pages one at a time (with or without the
 * script), Retake and Remove, at most 6, only photos and PDFs; Done: start counting reads them (FakeInvoiceReader:
 * the sample invoice A-4471) and goes straight on to the count; a page hard to read, a page that failed, nothing read
 * at all and another order's reference come back to the photo page; what was read and flagged is stored, never shown
 * on board; who may do what. Signed in as the office unless a request says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class InvoiceFlowTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired OrderReceiptFileRepository fileRepository;
    @Autowired DeliveryInvoiceRepository invoiceRepository;
    @Autowired DeliveryInvoiceLineRepository invoiceLineRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired FakeInvoiceReader fake;

    Ship ship;
    User master;
    User cook;
    ShipOrder order;
    long id;
    byte[] photo1;
    byte[] photo2;

    @BeforeEach
    void setUp() throws IOException {
        fake.reset();
        ship = ship("9074729", "MV Demo");
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Rank.CHIEF_COOK.role(), ship,
                Rank.CHIEF_COOK);
        order = order(ship);
        id = order.getId();
        photo1 = Files.readAllBytes(Path.of("samples/invoice/invoice-A-4471-p1-photo.jpg"));
        photo2 = Files.readAllBytes(Path.of("samples/invoice/invoice-A-4471-p2-photo.jpg"));
    }

    @AfterEach
    void resetFake() {
        fake.reset();
    }

    @Test
    void theCookPhotographsTheInvoiceAndGoesStraightOnToTheCount() throws Exception {
        fake.orderReference(String.format("SR-%06d", id));
        // Count delivery opens the photo page first.
        assertThat(as(cook, get("/ship/order?view=progress"))).contains("href=\"/ship/orders/" + id + "/invoice\"", "Count delivery");
        assertThat(as(cook, get("/ship/orders/{id}", id))).contains("href=\"/ship/orders/" + id + "/invoice\"");

        String page = as(cook, get("/ship/orders/{id}/invoice", id));
        assertThat(page).contains("Photograph the invoice", "Photograph every page. Hold it flat, in good light.",
                "Take a photo", "accept=\"image/*\" capture=\"environment\"", "Choose a file",
                "accept=\"image/*,application/pdf\"", "Upload",
                "href=\"/ship/orders/" + id + "/count\">Count without the invoice</a>")
                .doesNotContain("Done: start counting", "step-bar");

        // With the script: 204; without it: back to the page.
        perform(cook, upload("p1.jpg", photo1).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("p2.jpg", photo2)).andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        assertThat(pages).extracting(OrderReceiptFile::getInvoicePage).containsExactly(1, 2);
        assertThat(pages).allMatch(p -> p.getInvoicePageStatus() == InvoicePageStatus.WAITING
                && p.getUploadedBy().getId().equals(cook.getId()) && p.getFileContentType().equals("image/jpeg"));

        page = as(cook, get("/ship/orders/{id}/invoice", id));
        assertThat(page).contains("Page 1", "Page 2", "Retake", "Remove", "Add page", "Done: start counting",
                "src=\"/ship/orders/" + id + "/invoice/pages/" + pages.getFirst().getId() + "\"");
        // The thumbnail.
        mockMvc.perform(get("/ship/orders/{id}/invoice/pages/{f}", id, pages.getFirst().getId())
                        .with(user(new CurrentUser(cook))))
                .andExpect(status().isOk());

        // Done: start counting reads both pages, then on to the count.
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice/reading"));
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count"));
        mockMvc.perform(get("/ship/orders/{id}/invoice/reading/status", id).with(user(new CurrentUser(cook))))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.next").value("/ship/orders/" + id + "/count"));
        assertThat(fake.pagesRead()).containsExactly(1, 2);

        DeliveryInvoice invoice = invoiceRepository.findByOrderId(id).orElseThrow();
        JsonNode sample = fake.sample();
        assertThat(invoice.getStatus()).isEqualTo(DeliveryInvoiceStatus.READ);
        assertThat(invoice.getSupplierName()).isEqualTo("Kestrel Ship Supply S.A.");
        assertThat(invoice.getInvoiceNumber()).isEqualTo("A-4471");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(invoice.getNet()).isEqualByComparingTo(sample.path("net").asString());
        assertThat(invoice.getVat()).isEqualByComparingTo(sample.path("vat").asString());
        assertThat(invoice.getTotal()).isEqualByComparingTo(sample.path("total").asString());
        assertThat(invoice.getReadBy().getId()).isEqualTo(cook.getId());
        assertThat(invoice.getReadAt()).isNotNull();
        assertThat(invoice.getCheckNotes()).isNull();
        assertThat(invoice.getReferenceWarning()).isNull();
        List<DeliveryInvoiceLine> lines = invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        assertThat(lines).hasSize(40);
        assertThat(lines).noneMatch(DeliveryInvoiceLine::isFlagged);
        for (int i = 0; i < 40; i++) {
            JsonNode expected = sample.path("lines").path(i);
            DeliveryInvoiceLine line = lines.get(i);
            assertThat(line.getPage()).isEqualTo(expected.path("page").asInt());
            assertThat(line.getLineNumber()).isEqualTo(expected.path("no").asInt());
            assertThat(line.getDescription()).isEqualTo(expected.path("description").asString());
            assertThat(line.getQuantity()).isEqualByComparingTo(expected.path("quantity").asString());
            assertThat(line.getUnitPrice()).isEqualByComparingTo(expected.path("unitPrice").asString());
            assertThat(line.getAmount()).isEqualByComparingTo(expected.path("amount").asString());
            assertThat(line.getRawText()).startsWith("| " + expected.path("no").asInt() + " |");
        }
        assertThat(fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id))
                .allMatch(p -> p.getInvoicePageStatus() == InvoicePageStatus.READ
                        && Boolean.FALSE.equals(p.getInvoicePageCheck()));
        assertThat(audit("ORDER_INVOICE_PHOTOGRAPHED")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ", 2 pages");
        assertThat(audit("ORDER_INVOICE_READ")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ", 40 lines read, 0 flagged");

        // The count: the invoice's pages are not among his photos; Count delivery now goes straight there.
        String count = as(cook, get("/ship/orders/{id}/count", id));
        assertThat(count).contains("Invoice: 2 pages photographed", "href=\"/ship/orders/" + id + "/invoice\"")
                .doesNotContain("p1.jpg", "p2.jpg");
        assertThat(as(cook, get("/ship/order?view=progress"))).contains("href=\"/ship/orders/" + id + "/count\"")
                .doesNotContain("href=\"/ship/orders/" + id + "/invoice\"");
        // They are the Master's receipt files.
        assertThat(as(master, get("/ship/orders/{id}/receipt", id))).contains("Page 1 · Photograph the invoice",
                "Page 2 · Photograph the invoice");
        // Nothing went into the store, nothing was matched: the order's lines are as they were.
        assertThat(lineRepository.findAll()).allMatch(l -> l.getCountedQuantity() == null);
    }

    @Test
    void theMasterPhotographsTheInvoiceWhenNoCountWaits() throws Exception {
        fake.orderReference(String.valueOf(id));
        assertThat(as(master, get("/ship/order?view=progress"))).contains("href=\"/ship/orders/" + id + "/invoice\"", "Confirm receipt");
        assertThat(as(master, get("/ship/orders/{id}", id))).contains("href=\"/ship/orders/" + id + "/invoice\"");
        assertThat(as(master, get("/ship/orders/{id}/invoice", id)))
                .contains("href=\"/ship/orders/" + id + "/receipt\">Count without the invoice</a>");
        perform(master, upload("scan.pdf", "%PDF-1.4 scan".getBytes(StandardCharsets.ISO_8859_1)).param("js", "true"))
                .andExpect(status().isNoContent());
        perform(master, post("/ship/orders/{id}/invoice/read", id).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice/reading"));
        perform(master, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/receipt"));
        assertThat(invoiceRepository.findByOrderId(id).orElseThrow().getStatus()).isEqualTo(DeliveryInvoiceStatus.READ);
        assertThat(fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id).getFirst()
                .getFileContentType()).isEqualTo("application/pdf");

        // A count of the cook waiting for the Master: Confirm receipt goes to the receipt, as before.
        ShipOrder other = order(ship);
        other.setDeliveryCount(DeliveryCountStatus.SENT_TO_MASTER);
        other.setCountedBy(cook);
        other.setCountSentAt(Instant.now());
        orderRepository.save(other);
        assertThat(as(master, get("/ship/order?view=progress"))).contains("href=\"/ship/orders/" + other.getId() + "/receipt\"");
    }

    @Test
    void retakeAndRemoveKeepThePagesInOrder() throws Exception {
        fake.orderReference(String.valueOf(id));
        perform(cook, upload("a.jpg", photo1).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("b.jpg", photo2).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("c.jpg", JPEG).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(id).orElseThrow();
        // Page 3 has no table (the fake): hard to read, the other two read.
        assertThat(invoice.getStatus()).isEqualTo(DeliveryInvoiceStatus.PARTLY_READ);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(40);

        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        // Retake page 2: the same number, a new file, its lines dropped until it is read again.
        perform(cook, upload("b2.jpg", photo2).param("js", "true").param("replace", pages.get(1).getId().toString()))
                .andExpect(status().isNoContent());
        pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        assertThat(pages).extracting(OrderReceiptFile::getFileName).containsExactly("a.jpg", "b2.jpg", "c.jpg");
        assertThat(pages).extracting(OrderReceiptFile::getInvoicePage).containsExactly(1, 2, 3);
        assertThat(pages.get(1).getInvoicePageStatus()).isEqualTo(InvoicePageStatus.WAITING);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(22);

        // Remove page 1: the others move up, and so do their lines.
        perform(cook, post("/ship/orders/{id}/invoice/pages/{f}/remove", id, pages.getFirst().getId()).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        assertThat(pages).extracting(OrderReceiptFile::getFileName).containsExactly("b2.jpg", "c.jpg");
        assertThat(pages).extracting(OrderReceiptFile::getInvoicePage).containsExactly(1, 2);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).isEmpty();

        // Done again: only the page waiting is read (now page 1: the sample's page 1).
        fake.reset();
        fake.orderReference(String.valueOf(id));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        assertThat(fake.pagesRead()).containsExactly(1);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(22)
                .allMatch(l -> l.getPage() == 1);
    }

    @Test
    void aPageHardToReadAndAnotherOrdersReferenceComeBackToThePhotoPage() throws Exception {
        // Line 7 is lost in a fold of the paper; page 3 shows no table; the invoice names order SR-000052.
        fake.dropLine(7);
        perform(cook, upload("a.jpg", photo1).param("js", "true"));
        perform(cook, upload("b.jpg", photo2).param("js", "true"));
        perform(cook, upload("c.jpg", JPEG).param("js", "true"));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));

        String page = as(cook, get("/ship/orders/{id}/invoice", id));
        assertThat(page).contains("Page 1 is hard to read", "Page 3 is hard to read",
                "This page is hard to read: retake it flat, in good light, with the whole page in the picture.",
                "Continue anyway", "This invoice says order SR-000052. Check it is the right invoice.",
                "href=\"/ship/orders/" + id + "/count\">Continue</a>")
                .doesNotContain("Page 2 is hard to read", "line 7", "add up to", "could not be read");

        DeliveryInvoice invoice = invoiceRepository.findByOrderId(id).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(DeliveryInvoiceStatus.PARTLY_READ);
        assertThat(invoice.isOtherOrder()).isTrue();
        assertThat(invoice.getReferenceWarning()).isEqualTo("The invoice says order SR-000052. This is order " + id);
        // The failed checks are stored for the office.
        assertThat(invoice.getCheckNotes()).contains("Page 1: line 7 is missing")
                .contains("Page 1: the lines add up to")
                .contains("The lines add up to");
        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        assertThat(pages).extracting(OrderReceiptFile::getInvoicePageStatus).containsExactly(
                InvoicePageStatus.HARD_TO_READ, InvoicePageStatus.READ, InvoicePageStatus.HARD_TO_READ);
        assertThat(pages.getFirst().getInvoicePageCheck()).isTrue();
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(39);
    }

    @Test
    void aPageThatFailsKeepsTheOthersAndIsReadAgain() throws Exception {
        fake.orderReference(String.valueOf(id));
        fake.failPage(2, Reason.TIMEOUT);
        perform(cook, upload("a.jpg", photo1).param("js", "true"));
        perform(cook, upload("b.jpg", photo2).param("js", "true"));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(id).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(DeliveryInvoiceStatus.PARTLY_READ);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(22);
        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id);
        assertThat(pages.get(1).getInvoicePageStatus()).isEqualTo(InvoicePageStatus.TIMEOUT);
        String page = as(cook, get("/ship/orders/{id}/invoice", id));
        assertThat(page).contains("Page 2 could not be read", "Read again", "Retake",
                "action=\"/ship/orders/" + id + "/invoice/pages/" + pages.get(1).getId() + "/read\"")
                .doesNotContain("could not be read now");

        // Read again, that page only.
        fake.reset();
        fake.orderReference(String.valueOf(id));
        perform(cook, post("/ship/orders/{id}/invoice/pages/{f}/read", id, pages.get(1).getId()).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice/reading"));
        assertThat(fake.pagesRead()).containsExactly(2);
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count"));
        assertThat(invoiceRepository.findByOrderId(id).orElseThrow().getStatus()).isEqualTo(DeliveryInvoiceStatus.READ);
        assertThat(invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())).hasSize(40);
    }

    @Test
    void noNetworkToTheReaderKeepsThePhotos() throws Exception {
        fake.fail(Reason.PROVIDER_ERROR);
        perform(cook, upload("a.jpg", photo1).param("js", "true"));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        assertThat(invoiceRepository.findByOrderId(id).orElseThrow().getStatus()).isEqualTo(DeliveryInvoiceStatus.FAILED);
        String page = as(cook, get("/ship/orders/{id}/invoice", id));
        assertThat(page).contains("The invoice could not be read now. Count without it, the photos are kept.",
                "name=\"again\" value=\"true\"", "Count without the invoice")
                .doesNotContain("Page 1 could not be read");
        // The photo is a receipt file all the same.
        assertThat(fileRepository.findAllByOrderIdOrderByIdAsc(id)).hasSize(1);
        assertThat(audit("ORDER_INVOICE_READ")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ", 0 lines read, 0 flagged, 1 page not read");

        // Later, Read again.
        fake.reset();
        fake.orderReference(String.valueOf(id));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).param("again", "true").with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice/reading"));
        assertThat(invoiceRepository.findByOrderId(id).orElseThrow().getStatus()).isEqualTo(DeliveryInvoiceStatus.READ);
        // Read again is not a new photograph.
        assertThat(audit("ORDER_INVOICE_PHOTOGRAPHED")).hasSize(1);
    }

    @Test
    void flaggedLinesAreStoredButNotShownOnBoard() throws Exception {
        fake.orderReference(String.valueOf(id));
        fake.change(3, "", null, null);       // quantity unreadable: recovered from price and amount
        fake.change(9, "", "", null);         // quantity and price unreadable: quantity not read
        fake.change(13, null, null, "11040"); // the amount's comma lost: recovered
        fake.lowConfidence(5);                // check
        perform(cook, upload("a.jpg", photo1).param("js", "true"));
        perform(cook, post("/ship/orders/{id}/invoice/read", id).with(csrf()));
        // Nothing for the ship to act on: straight on to the count.
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count"));
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(id).orElseThrow();
        List<DeliveryInvoiceLine> lines = invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        DeliveryInvoiceLine onions = lines.get(2);
        assertThat(onions.isCorrected()).isTrue();
        assertThat(onions.getReadQuantity()).isNull();
        assertThat(onions.getQuantity()).isEqualByComparingTo("15");
        DeliveryInvoiceLine lettuce = lines.get(8);
        assertThat(lettuce.isQuantityNotRead()).isTrue();
        assertThat(lettuce.getQuantity()).isNull();
        DeliveryInvoiceLine milk = lines.get(12);
        assertThat(milk.isCorrected()).isTrue();
        assertThat(milk.getReadAmount()).isEqualByComparingTo("11040");
        assertThat(milk.getAmount()).isEqualByComparingTo("110.40");
        DeliveryInvoiceLine lemons = lines.get(4);
        assertThat(lemons.isNeedsCheck()).isTrue();
        assertThat(lemons.getConfidence()).isEqualTo(0.5);
        assertThat(lines.stream().filter(DeliveryInvoiceLine::isFlagged)).hasSize(4);
        assertThat(audit("ORDER_INVOICE_READ")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ", 22 lines read, 4 flagged");
        // The ship sees no flags: the page shows the photos only (the office sees the checks).
        assertThat(as(cook, get("/ship/orders/{id}/invoice", id))).doesNotContain("Corrected", "Check this line",
                "Quantity not read", "flagged", "Onions", "15.00");
    }

    @Test
    void onlyPhotosAndPdfsUpToSixPages() throws Exception {
        perform(cook, upload("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)).param("js", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .isEqualTo("notes.txt: not a photo or a PDF. Use a JPEG, PNG, WebP or HEIC photo, or a PDF."));
        perform(cook, upload("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P'};
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        perform(cook, upload("IMG_0001.HEIC", heic).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("page.webp", webp).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("page.png", png).param("js", "true")).andExpect(status().isNoContent());
        // No extension at all (some phones): the content decides.
        perform(cook, upload("image", JPEG).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("5.jpg", JPEG).param("js", "true")).andExpect(status().isNoContent());
        perform(cook, upload("6.jpg", JPEG).param("js", "true")).andExpect(status().isNoContent());
        assertThat(fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id))
                .extracting(OrderReceiptFile::getFileContentType)
                .containsExactly("image/heic", "image/webp", "image/png", "image/jpeg", "image/jpeg", "image/jpeg");
        perform(cook, upload("7.jpg", JPEG).param("js", "true")).andExpect(status().isUnprocessableEntity())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .isEqualTo("An invoice has at most 6 pages here."));
        assertThat(as(cook, get("/ship/orders/{id}/invoice", id)))
                .contains("That is 6 pages, the most one invoice can have.").doesNotContain("Take a photo");
    }

    @Test
    void whoMayPhotographAndWhen() throws Exception {
        // The office and crew members: no.
        mockMvc.perform(get("/ship/orders/{id}/invoice", id)).andExpect(status().isForbidden());
        User crew = userService.createUser("crew@example.com", "Deck Hand", "secret123", Rank.ABLE_SEAMAN.role(), ship,
                Rank.ABLE_SEAMAN);
        perform(crew, get("/ship/orders/{id}/invoice", id)).andExpect(status().isForbidden());
        // Another ship's order: not found.
        Ship otherShip = ship("9300001", "ALDER");
        ShipOrder otherOrder = order(otherShip);
        perform(cook, get("/ship/orders/{id}/invoice", otherOrder.getId())).andExpect(status().isNotFound());
        perform(cook, upload(otherOrder.getId(), "a.jpg", JPEG).param("js", "true")).andExpect(status().isNotFound());

        // While the pages are being read, they cannot change.
        perform(cook, upload("a.jpg", JPEG).param("js", "true")).andExpect(status().isNoContent());
        DeliveryInvoice reading = new DeliveryInvoice();
        reading.setOrder(order);
        reading.setStatus(DeliveryInvoiceStatus.READING);
        reading.setStartedAt(Instant.now());
        invoiceRepository.save(reading);
        perform(cook, upload("b.jpg", JPEG).param("js", "true")).andExpect(status().isConflict());
        perform(cook, get("/ship/orders/{id}/invoice", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice/reading"));
        perform(cook, get("/ship/orders/{id}/invoice/reading", id)).andExpect(status().isOk());
        assertThat(as(cook, get("/ship/orders/{id}/invoice/reading", id))).contains("Reading page 1 of 1",
                "http-equiv=\"refresh\"");
        // A reading that never finished (a restart) is given up after a while.
        reading.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        invoiceRepository.save(reading);
        perform(cook, get("/ship/orders/{id}/invoice/reading", id))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/invoice"));
        assertThat(fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(id).getFirst()
                .getInvoicePageStatus()).isEqualTo(InvoicePageStatus.TIMEOUT);

        // The cook's count with the Master: no more pages from him.
        order.setDeliveryCount(DeliveryCountStatus.SENT_TO_MASTER);
        orderRepository.save(order);
        perform(cook, get("/ship/orders/{id}/invoice", id)).andExpect(redirectedUrl("/ship/orders/" + id + "/count"));
        perform(cook, upload("b.jpg", JPEG).param("js", "true")).andExpect(status().isConflict());

        // Received: never read again, never changed.
        order.setStatus(ShipOrderStatus.CLOSED);
        orderRepository.save(order);
        perform(master, get("/ship/orders/{id}/invoice", id)).andExpect(redirectedUrl("/ship/orders/" + id));
        perform(master, post("/ship/orders/{id}/invoice/read", id).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id));
        perform(master, upload("b.jpg", JPEG).param("js", "true")).andExpect(status().isConflict());
        assertThat(fake.pagesRead()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private MockMultipartHttpServletRequestBuilder upload(String name, byte[] content) {
        return upload(id, name, content);
    }

    private MockMultipartHttpServletRequestBuilder upload(long orderId, String name, byte[] content) {
        MockMultipartHttpServletRequestBuilder request = multipart("/ship/orders/{id}/invoice/pages", orderId);
        request.file(new MockMultipartFile("file", name, "application/octet-stream", content));
        request.with(csrf());
        return request;
    }

    private ResultActions perform(User user, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(user))));
    }

    private String as(User user, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(user)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
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

    // A CONFIRMED order of the ship: 25 kg tomatoes and 30 eggs, delivered today at Antwerp.
    private ShipOrder order(Ship of) {
        ShipOrder created = new ShipOrder();
        created.setShip(of);
        created.setStatus(ShipOrderStatus.CONFIRMED);
        created.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        created.setSentBy(master);
        created.setSentAt(Instant.now().minus(1, ChronoUnit.DAYS));
        created.setRequestedPort("Rotterdam");
        created.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        created.setConfirmedPort("Antwerp");
        created.setDeliveryDate(LocalDate.now(ZoneOffset.UTC));
        orderRepository.save(created);
        line(created, item(of, "Tomatoes", "kg"), "25");
        line(created, item(of, "Eggs", "pcs"), "30");
        return created;
    }

    private void line(ShipOrder of, Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(of);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        line.setOriginalQuantity(new BigDecimal(quantity));
        of.getLines().add(lineRepository.save(line));
    }

    private Item item(Ship of, String name, String unit) {
        Item item = new Item();
        item.setShip(of);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(ItemCategory.PROVISIONS);
        item.setCreatedAt(Instant.now());
        item.setActive(true);
        return itemRepository.save(item);
    }
}
