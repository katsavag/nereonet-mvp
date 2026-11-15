package com.nereonix.nereonet_mvp.core.order;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReader;
import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException;
import com.nereonix.nereonet_mvp.core.ai.PageReading;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.CheckedLine;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.PageInput;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Reading the photographed invoice of an order. start (on "Done: start counting", or "Read again") marks the
 * pages to read (WAITING) and the invoice READING; the pages are then read one at a time, in the background
 * (app.invoice.read-in-background, false in the tests: then at once, in the caller's transaction), each page saved as
 * soon as it is read, so a page that fails keeps the others' lines. When no page waits, finish runs InvoiceChecks over
 * every page read, stores the values and flags, compares the order reference and sets the status: READ, PARTLY_READ
 * or FAILED (no line read). Re-reading replaces a page's lines while the order is CONFIRMED, never after.
 * Only the page images go to the InvoiceReader. Audit: "Invoice photographed" (pages) and "Invoice read" (lines read,
 * lines flagged). A reading that never finishes (the server restarted meanwhile) is given up after
 * app.invoice.reading-stalled-after: its waiting pages count as timed out.
 */
@Service
public class InvoiceReadingService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceReadingService.class);
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** What start did. */
    public enum Start {
        // Reading: follow it on the progress page.
        STARTED,
        // Every page is read already: on to the count.
        NOTHING_TO_READ,
        // No reader configured (no OCR models): the photos are kept, on to the count.
        NOT_CONFIGURED,
        NO_PAGES,
        ALREADY_READING,
        NOT_CONFIRMED,
        // The Chief cook's count is with the Master.
        COUNT_SENT
    }

    private final OrderReceiptFileRepository fileRepository;
    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository lineRepository;
    private final LibraryFileStorage fileStorage;
    private final InvoiceReader reader;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;
    private final boolean background;
    private final double minConfidence;
    private final Duration stalledAfter;

    public InvoiceReadingService(OrderReceiptFileRepository fileRepository, DeliveryInvoiceRepository invoiceRepository,
                                 DeliveryInvoiceLineRepository lineRepository, LibraryFileStorage fileStorage,
                                 InvoiceReader reader, AuditService auditService,
                                 EntityManager entityManager, PlatformTransactionManager transactionManager,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                 @Value("${app.invoice.read-in-background:true}") boolean background,
                                 @Value("${app.invoice.min-confidence:0.85}") double minConfidence,
                                 @Value("${app.invoice.reading-stalled-after:15m}") Duration stalledAfter) {
        this.fileRepository = fileRepository;
        this.invoiceRepository = invoiceRepository;
        this.lineRepository = lineRepository;
        this.fileStorage = fileStorage;
        this.reader = reader;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.background = background;
        this.minConfidence = minConfidence;
        this.stalledAfter = stalledAfter;
    }

    /** The invoice of an order, if its reading ever started. */
    @Transactional(readOnly = true)
    public Optional<DeliveryInvoice> invoice(Long orderId) {
        return invoiceRepository.findByOrderId(orderId);
    }

    /** The lines read, by page (P63 shows their quantities in the count; P64 the office's checks). */
    @Transactional(readOnly = true)
    public List<DeliveryInvoiceLine> lines(Long orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .map(invoice -> lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId()))
                .orElse(List.of());
    }

    /**
     * Starts reading the pages of a CONFIRMED order's invoice, by the vessel's enabled Chief cook (while his count is
     * not with the Master) or Master. pageFileId null ("Done: start counting", or "Read again" for the whole invoice):
     * every page not read yet, the failed ones again; else that one page again ("Read again" on a page). done: from
     * "Done: start counting", recorded as "Invoice photographed". 404 for another ship's order or page.
     */
    @Transactional
    public Start start(User user, Long orderId, Long pageFileId, boolean done) {
        ShipOrder order = lockOrder(user, orderId);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Start.NOT_CONFIRMED;
        }
        if (user.getRole() == Role.CHIEF_COOK && !order.getDeliveryCountStatus().editable()) {
            return Start.COUNT_SENT;
        }
        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId);
        if (pages.isEmpty()) {
            return Start.NO_PAGES;
        }
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (invoice != null && invoice.getStatus() == DeliveryInvoiceStatus.READING && !stalled(invoice)) {
            return Start.ALREADY_READING;
        }
        if (done) {
            auditService.record("ORDER_INVOICE_PHOTOGRAPHED", order.getShip(), "Order #" + orderId + ", "
                    + pages.size() + (pages.size() == 1 ? " page" : " pages"), user);
        }
        if (!reader.configured()) {
            return Start.NOT_CONFIGURED;
        }
        int waiting = 0;
        for (OrderReceiptFile page : pages) {
            InvoicePageStatus status = page.getInvoicePageStatus();
            boolean read = pageFileId == null
                    ? status == null || status == InvoicePageStatus.WAITING || status.failed()
                    : page.getId().equals(pageFileId);
            if (read) {
                page.setInvoicePageStatus(InvoicePageStatus.WAITING);
                waiting++;
            }
        }
        if (pageFileId != null && waiting == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (waiting == 0) {
            return Start.NOTHING_TO_READ;
        }
        if (invoice == null) {
            invoice = new DeliveryInvoice();
            invoice.setOrder(order);
        }
        invoice.setStatus(DeliveryInvoiceStatus.READING);
        invoice.setStartedAt(Instant.now());
        invoice.setReadBy(user);
        invoiceRepository.save(invoice);
        fileRepository.flush();
        Long id = orderId;
        if (background && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> readWaiting(id));
                }
            });
        } else {
            readWaiting(id);
        }
        return Start.STARTED;
    }

    /**
     * Gives up a reading that has not finished after app.invoice.reading-stalled-after (the server restarted while it
     * ran): the pages still waiting count as timed out. True when it did.
     */
    @Transactional
    public boolean giveUpIfStalled(Long orderId) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (invoice == null || invoice.getStatus() != DeliveryInvoiceStatus.READING || !stalled(invoice)) {
            return false;
        }
        for (OrderReceiptFile page : fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId)) {
            if (page.getInvoicePageStatus() == InvoicePageStatus.WAITING) {
                page.setInvoicePageStatus(InvoicePageStatus.TIMEOUT);
            }
        }
        finish(orderId);
        log.warn("Invoice of order {}: reading given up after {}", orderId, stalledAfter);
        return true;
    }

    private boolean stalled(DeliveryInvoice invoice) {
        return invoice.getStartedAt() == null || invoice.getStartedAt().plus(stalledAfter).isBefore(Instant.now());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record Next(Long fileId, int page, String contentType, String storagePath) {
    }

    // Reads every page waiting, one at a time (each saved in its own transaction when in the background), then
    // finishes. Never leaves the invoice READING: anything unexpected fails the pages still waiting.
    void readWaiting(Long orderId) {
        try {
            while (true) {
                Next next = transactions.execute(status -> fileRepository
                        .findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId).stream()
                        .filter(page -> page.getInvoicePageStatus() == InvoicePageStatus.WAITING)
                        .findFirst()
                        .map(page -> new Next(page.getId(), page.getInvoicePage(), page.getFileContentType(),
                                page.getStoragePath()))
                        .orElse(null));
                if (next == null) {
                    break;
                }
                PageReading reading = null;
                InvoicePageStatus failure = null;
                try {
                    reading = reader.readPage(new InvoiceReader.Page(next.page(), bytes(next.storagePath()),
                            next.contentType()));
                } catch (InvoiceReadingException e) {
                    failure = InvoicePageStatus.valueOf(e.reason().name());
                }
                PageReading read = reading;
                InvoicePageStatus failed = failure;
                transactions.executeWithoutResult(status -> savePage(orderId, next, read, failed));
            }
            transactions.executeWithoutResult(status -> finish(orderId));
        } catch (RuntimeException e) {
            log.error("Invoice of order {}: reading stopped ({})", orderId, e.getClass().getSimpleName());
            transactions.executeWithoutResult(status -> {
                for (OrderReceiptFile page : fileRepository
                        .findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId)) {
                    if (page.getInvoicePageStatus() == InvoicePageStatus.WAITING) {
                        page.setInvoicePageStatus(InvoicePageStatus.PROVIDER_ERROR);
                    }
                }
                finish(orderId);
            });
        }
    }

    private byte[] bytes(String storagePath) {
        try (InputStream in = fileStorage.load(storagePath).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new InvoiceReadingException(InvoiceReadingException.Reason.PROVIDER_ERROR, "The page's file is missing");
        }
    }

    // One page read (or failed): its status and total, its lines as read (the checks come in finish), and what its
    // text says of the invoice.
    private void savePage(Long orderId, Next next, PageReading reading, InvoicePageStatus failure) {
        OrderReceiptFile page = fileRepository.findById(next.fileId()).orElse(null);
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (page == null || invoice == null || page.getOrder().getStatus() != ShipOrderStatus.CONFIRMED) {
            return;
        }
        lineRepository.deleteAll(lineRepository.findAllByInvoiceIdAndPage(invoice.getId(), next.page()));
        if (reading == null) {
            page.setInvoicePageStatus(failure);
            page.setInvoicePageTotal(null);
            return;
        }
        page.setInvoicePageStatus(reading.tableFound() ? InvoicePageStatus.READ : InvoicePageStatus.HARD_TO_READ);
        page.setInvoicePageTotal(money(reading.pageTotal()));
        for (ReadLine read : reading.lines()) {
            DeliveryInvoiceLine line = new DeliveryInvoiceLine();
            line.setInvoice(invoice);
            line.setPage(next.page());
            line.setLineNumber(read.no());
            line.setDescription(cut(read.description(), DeliveryInvoiceLine.DESCRIPTION_MAX_LENGTH));
            line.setUnit(cut(read.unit(), 30));
            line.setReadQuantity(fit(read.quantity(), 16, 3));
            line.setReadUnitPrice(fit(read.unitPrice(), 15, 4));
            line.setReadAmount(fit(read.amount(), 17, 2));
            line.setRawText(cut(read.raw(), DeliveryInvoiceLine.RAW_MAX_LENGTH));
            line.setConfidence(read.confidence());
            lineRepository.save(line);
        }
        if (reading.supplier() != null) {
            invoice.setSupplierName(cut(reading.supplier(), 200));
        }
        if (reading.invoiceNumber() != null) {
            invoice.setInvoiceNumber(cut(reading.invoiceNumber(), 60));
        }
        if (reading.date() != null) {
            invoice.setInvoiceDate(reading.date());
        }
        if (reading.orderReference() != null) {
            invoice.setOrderReference(cut(reading.orderReference(), 60));
        }
        if (reading.net() != null) {
            invoice.setNet(money(reading.net()));
        }
        if (reading.vat() != null) {
            invoice.setVat(money(reading.vat()));
        }
        if (reading.total() != null) {
            invoice.setTotal(money(reading.total()));
        }
    }

    // Every page read: the checks over all of them, the order reference, the status; recorded as "Invoice read".
    void finish(Long orderId) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (invoice == null) {
            return;
        }
        ShipOrder order = invoice.getOrder();
        List<OrderReceiptFile> pages = fileRepository.findAllByOrderIdAndInvoicePageNotNullOrderByInvoicePageAsc(orderId);
        List<DeliveryInvoiceLine> stored = lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        List<PageInput> inputs = new ArrayList<>();
        List<DeliveryInvoiceLine> inOrder = new ArrayList<>();
        int failedPages = 0;
        for (OrderReceiptFile page : pages) {
            InvoicePageStatus status = page.getInvoicePageStatus();
            List<DeliveryInvoiceLine> onPage = stored.stream().filter(l -> l.getPage() == page.getInvoicePage()).toList();
            if (status == null || status == InvoicePageStatus.WAITING || status.failed()) {
                failedPages += status != null && status.failed() ? 1 : 0;
                page.setInvoicePageCheck(null);
                continue;
            }
            boolean table = status == InvoicePageStatus.READ || !onPage.isEmpty();
            List<ReadLine> reads = onPage.stream().map(l -> new ReadLine(l.getLineNumber(), l.getDescription(),
                    l.getUnit(), l.getReadQuantity(), l.getReadUnitPrice(), l.getReadAmount(), l.getRawText(),
                    l.getConfidence())).toList();
            inputs.add(new PageInput(page.getInvoicePage(), table, reads, page.getInvoicePageTotal()));
            inOrder.addAll(onPage);
        }
        InvoiceChecks.Result result = InvoiceChecks.check(inputs, invoice.getNet(), invoice.getVat(), invoice.getTotal(),
                minConfidence);
        for (int i = 0; i < inOrder.size(); i++) {
            DeliveryInvoiceLine line = inOrder.get(i);
            CheckedLine checked = result.lines().get(i);
            line.setQuantity(fit(checked.quantity(), 12, 3));
            line.setUnitPrice(fit(checked.unitPrice(), 10, 4));
            line.setAmount(fit(checked.amount(), 12, 2));
            line.setCorrected(checked.corrected());
            line.setNeedsCheck(checked.check());
            line.setQuantityNotRead(checked.quantityNotRead());
        }
        int hardPages = 0;
        for (OrderReceiptFile page : pages) {
            InvoicePageStatus status = page.getInvoicePageStatus();
            if (status == InvoicePageStatus.READ || status == InvoicePageStatus.HARD_TO_READ) {
                boolean hard = result.hardToRead().contains(page.getInvoicePage());
                page.setInvoicePageStatus(hard ? InvoicePageStatus.HARD_TO_READ : InvoicePageStatus.READ);
                page.setInvoicePageCheck(result.pagesToCheck().contains(page.getInvoicePage()));
                hardPages += hard ? 1 : 0;
            }
        }
        String notes = String.join("\n", result.problems().stream().map(InvoiceChecks.Problem::text).toList());
        invoice.setCheckNotes(notes.isEmpty() ? null : cut(notes, DeliveryInvoice.CHECK_NOTES_MAX_LENGTH));
        Reference reference = reference(invoice.getOrderReference(), orderId);
        invoice.setReferenceWarning(reference.warning());
        invoice.setOtherOrder(reference.otherOrder());
        int linesRead = inOrder.size();
        invoice.setStatus(linesRead == 0 ? DeliveryInvoiceStatus.FAILED
                : failedPages + hardPages == 0 ? DeliveryInvoiceStatus.READ : DeliveryInvoiceStatus.PARTLY_READ);
        invoice.setReadAt(Instant.now());
        auditService.record("ORDER_INVOICE_READ", order.getShip(), "Order #" + orderId + ", " + linesRead
                + (linesRead == 1 ? " line" : " lines") + " read, " + result.flagged() + " flagged"
                + (failedPages > 0 ? ", " + failedPages + (failedPages == 1 ? " page" : " pages") + " not read" : ""),
                invoice.getReadBy());
        log.info("Invoice of order {}: {} lines read, {} flagged, {} pages failed, {} hard to read", orderId,
                linesRead, result.flagged(), failedPages, hardPages);
    }

    /** What the invoice's order reference says of the order: a warning for the office, and whether the ship is told. */
    record Reference(String warning, boolean otherOrder) {
    }

    /**
     * The order reference compared with the order: the same when its last group of digits is the order's number
     * ("SR-000012", "12" for order 12). Missing: a warning, not shown on board. Different: a warning, and the ship is
     * told to check it is the right invoice.
     */
    static Reference reference(String orderReference, long orderId) {
        if (orderReference == null || orderReference.isBlank()) {
            return new Reference("The invoice shows no order reference. This is order " + orderId, false);
        }
        Matcher matcher = DIGITS.matcher(orderReference);
        String digits = null;
        while (matcher.find()) {
            digits = matcher.group();
        }
        if (digits != null) {
            String number = digits.replaceFirst("^0+(?=\\d)", "");
            if (number.equals(String.valueOf(orderId))) {
                return new Reference(null, false);
            }
        }
        return new Reference("The invoice says order " + orderReference + ". This is order " + orderId, digits != null);
    }

    private ShipOrder lockOrder(User user, Long orderId) {
        if (user == null || !user.isEnabled() || user.getShip() == null
                || !(user.getRole() == Role.MASTER || user.getRole() == Role.CHIEF_COOK)) {
            throw new AccessDeniedException("Only the Master and the Chief cook photograph the invoice");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !order.getShip().getId().equals(user.getShip().getId())
                || order.getStatus() == ShipOrderStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }

    private static BigDecimal money(BigDecimal value) {
        return fit(value, 12, 2);
    }

    // The value when it fits the column (at most this many whole digits), rounded to its scale; null otherwise.
    private static BigDecimal fit(BigDecimal value, int wholeDigits, int scale) {
        if (value == null) {
            return null;
        }
        BigDecimal rounded = value.setScale(scale, java.math.RoundingMode.HALF_UP);
        return rounded.precision() - rounded.scale() > wholeDigits ? null : rounded;
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        return value.length() > max ? value.substring(0, max) : value;
    }
}
