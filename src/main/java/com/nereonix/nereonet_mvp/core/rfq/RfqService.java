package com.nereonix.nereonet_mvp.core.rfq;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.supplier.ChannelType;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierChannel;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Requests for quotes (P44): from a vessel order the ship has sent, the office asks several suppliers for a price,
 * enters their answers, compares them (RfqComparison) and awards one quote for the whole RFQ. A sealed comparison:
 * every supplier gets its own request, and nothing a supplier receives names another supplier or a price.
 * How a request reaches a supplier is its SupplierChannel's business (only email today). Every change locks the
 * vessel order's row first, so an order never has two OPEN RFQs and an award never meets a cancel half way.
 * Office users only (AccessDeniedException otherwise).
 */
@Service
public class RfqService {

    /** What the office asks: where and when to deliver, by when to answer, and whom. */
    public record Request(String port, LocalDate deliveryDate, LocalDate replyBy, Collection<Long> supplierIds) {
    }

    /** What send did. */
    public enum SendOutcome {
        DONE,
        // The order is not SENT (confirmed, closed meanwhile): nothing sent.
        ORDER_NOT_SENT,
        // The order has an OPEN RFQ already: nothing sent.
        ALREADY_OPEN,
        // None of the suppliers chosen is an active supplier: nothing sent.
        NO_SUPPLIERS
    }

    /** send's result: the RFQ (when DONE) and how many suppliers the channel failed to reach. */
    public record Sent(SendOutcome outcome, Rfq rfq, int failed) {
    }

    /** One line of an entered quote: the RFQ line and the supplier's figures. unitPrice null: not offered. */
    public record LineEntry(Long lineId, BigDecimal unitPrice, BigDecimal quantityOffered, BigDecimal packSize,
                            String packUnit, String note) {
    }

    /** The supplier's PDF, optional. */
    public record QuoteFile(InputStream content, String fileName, long size) {
    }

    /** An answer entered by the office. Values already checked by the form (QuoteForm). */
    public record QuoteEntry(String currency, BigDecimal deliveryCost, LocalDate deliveryDate, LocalDate validUntil,
                             List<LineEntry> lines, QuoteFile file) {
    }

    /** What a change of an RFQ did. */
    public enum Change {
        DONE,
        // The RFQ is no longer OPEN (awarded or cancelled meanwhile): nothing changed.
        CLOSED,
        // The quote cannot do this in its state (award a quote not QUOTED, send again one QUOTED).
        NOT_ALLOWED
    }

    /** "Awarded to VANOS · EUR 1,240.50" on the vessel order, and the Confirm form's prefill. */
    public record Award(Rfq rfq, RfqQuote quote) {

        public String supplierName() {
            return quote.getSupplier().getName();
        }

        public String currency() {
            return quote.getCurrency();
        }

        public BigDecimal total() {
            return quote.grandTotal();
        }
    }

    private final RfqRepository rfqRepository;
    private final RfqLineRepository lineRepository;
    private final RfqQuoteRepository quoteRepository;
    private final RfqQuoteLineRepository quoteLineRepository;
    private final SupplierRepository supplierRepository;
    private final Map<ChannelType, SupplierChannel> channels = new EnumMap<>(ChannelType.class);
    private final ObjectProvider<RfqAwardListener> awardListeners;
    private final AuditService auditService;
    private final LibraryFileStorage fileStorage;
    private final EntityManager entityManager;

    public RfqService(RfqRepository rfqRepository, RfqLineRepository lineRepository,
                      RfqQuoteRepository quoteRepository, RfqQuoteLineRepository quoteLineRepository,
                      SupplierRepository supplierRepository, List<SupplierChannel> channels,
                      ObjectProvider<RfqAwardListener> awardListeners, AuditService auditService,
                      LibraryFileStorage fileStorage, EntityManager entityManager) {
        this.rfqRepository = rfqRepository;
        this.lineRepository = lineRepository;
        this.quoteRepository = quoteRepository;
        this.quoteLineRepository = quoteLineRepository;
        this.supplierRepository = supplierRepository;
        for (SupplierChannel channel : channels) {
            this.channels.put(channel.type(), channel);
        }
        this.awardListeners = awardListeners;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.entityManager = entityManager;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    /** An order's RFQs, newest first. */
    @Transactional(readOnly = true)
    public List<Rfq> forOrder(Long orderId) {
        return rfqRepository.findAllByOrderIdOrderByIdDesc(orderId);
    }

    /** The order's OPEN RFQ, if any. */
    @Transactional(readOnly = true)
    public Optional<Rfq> open(Long orderId) {
        return rfqRepository.findByOrderIdAndStatus(orderId, RfqStatus.OPEN);
    }

    /** One RFQ of this order. */
    @Transactional(readOnly = true)
    public Optional<Rfq> find(Long orderId, Long rfqId) {
        return rfqRepository.findByIdAndOrderId(rfqId, orderId);
    }

    /** One supplier's slot on this RFQ. */
    @Transactional(readOnly = true)
    public Optional<RfqQuote> quote(Rfq rfq, Long quoteId) {
        return quoteRepository.findByIdAndRfqId(quoteId, rfq.getId());
    }

    /** The order's latest award, if any. */
    @Transactional(readOnly = true)
    public Optional<Award> awarded(Long orderId) {
        return rfqRepository.findFirstByOrderIdAndStatusOrderByClosedAtDescIdDesc(orderId, RfqStatus.AWARDED)
                .filter(rfq -> rfq.getAwardedQuote() != null)
                .map(rfq -> new Award(rfq, rfq.getAwardedQuote()));
    }

    /** True when an RFQ can be started for this order: it is SENT and has no OPEN RFQ. */
    @Transactional(readOnly = true)
    public boolean canRequest(ShipOrder order) {
        return order.getStatus() == ShipOrderStatus.SENT && open(order.getId()).isEmpty();
    }

    /**
     * The suppliers the RFQ form offers: the active ones, those whose ports served mention the port first, each part
     * by name.
     */
    @Transactional(readOnly = true)
    public List<Supplier> suppliersFor(String port) {
        return supplierRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .sorted(Comparator.comparing((Supplier s) -> !s.serves(port)))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sends a new RFQ for a SENT order: copies the order's lines (item, unit, category, quantity), makes one quote
     * slot per chosen active supplier and sends each its request through the supplier's channel. A supplier the
     * channel cannot reach is FAILED (and can be sent again); the others are SENT. Recorded as "RFQ sent".
     * IllegalArgumentException for a missing or too long port or a missing date.
     */
    @Transactional
    public Sent send(User officeUser, Long orderId, Request request) {
        checkOffice(officeUser);
        String port = request.port() == null ? "" : request.port().strip();
        if (port.isEmpty() || port.length() > Rfq.PORT_MAX_LENGTH || request.deliveryDate() == null
                || request.replyBy() == null) {
            throw new IllegalArgumentException("An RFQ needs a port, a delivery date and a reply-by date");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || order.getStatus() != ShipOrderStatus.SENT) {
            return new Sent(SendOutcome.ORDER_NOT_SENT, null, 0);
        }
        if (open(orderId).isPresent()) {
            return new Sent(SendOutcome.ALREADY_OPEN, null, 0);
        }
        Set<Long> ids = request.supplierIds() == null ? Set.of() : new LinkedHashSet<>(request.supplierIds());
        List<Supplier> suppliers = supplierRepository.findAllById(ids).stream().filter(Supplier::isActive)
                .sorted(Comparator.comparing(Supplier::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (suppliers.isEmpty()) {
            return new Sent(SendOutcome.NO_SUPPLIERS, null, 0);
        }

        Rfq rfq = new Rfq();
        rfq.setOrder(order);
        rfq.setStatus(RfqStatus.OPEN);
        rfq.setPort(port);
        rfq.setDeliveryDate(request.deliveryDate());
        rfq.setReplyBy(request.replyBy());
        rfq.setSentBy(officeUser);
        rfq.setSentAt(Instant.now());
        rfqRepository.save(rfq);
        for (ShipOrderLine orderLine : order.getLines()) {
            // P50: a line the office removed is not ordered.
            if (orderLine.isRemoved()) {
                continue;
            }
            RfqLine line = new RfqLine();
            line.setRfq(rfq);
            line.setItem(orderLine.getItem());
            line.setItemName(orderLine.getItem().getName());
            line.setUnit(orderLine.getItem().getUnit());
            line.setCategory(orderLine.getItem().getCategory());
            line.setQuantity(orderLine.getQuantity());
            lineRepository.save(line);
            rfq.getLines().add(line);
        }
        for (Supplier supplier : suppliers) {
            RfqQuote quote = new RfqQuote();
            quote.setRfq(rfq);
            quote.setSupplier(supplier);
            quote.setStatus(QuoteStatus.SENT);
            quote.setCurrency(supplier.getCurrency());
            quoteRepository.save(quote);
            rfq.getQuotes().add(quote);
        }
        int failed = 0;
        for (RfqQuote quote : rfq.getQuotes()) {
            if (!deliver(rfq, quote)) {
                failed++;
            }
        }
        auditService.record("RFQ_SENT", order.getShip(), rfq.number() + " for order #" + order.getId() + ", "
                + rfq.getLines().size() + (rfq.getLines().size() == 1 ? " line, " : " lines, ") + port + ", delivery "
                + rfq.getDeliveryDate() + ", reply by " + rfq.getReplyBy() + ", to "
                + suppliers.stream().map(Supplier::getName).collect(Collectors.joining(", "))
                + (failed > 0 ? " (" + failed + " failed)" : ""), officeUser);
        return new Sent(SendOutcome.DONE, rfq, failed);
    }

    /**
     * Sends the request to one supplier again (a FAILED one, or a SENT one that lost the email), while the RFQ is
     * OPEN. Recorded as "RFQ sent" with the supplier. CLOSED or NOT_ALLOWED (quoted or declined): nothing sent.
     * Returns DONE also when the channel failed again; the quote then stays FAILED with the new reason.
     */
    @Transactional
    public Change resend(User officeUser, Rfq rfq, RfqQuote quote) {
        checkOffice(officeUser);
        if (!stillOpen(rfq)) {
            return Change.CLOSED;
        }
        Rfq locked = rfq;
        if (quote.getStatus() != QuoteStatus.FAILED && quote.getStatus() != QuoteStatus.SENT) {
            return Change.NOT_ALLOWED;
        }
        boolean ok = deliver(locked, quote);
        auditService.record("RFQ_SENT", locked.getOrder().getShip(), locked.number() + ", sent again to "
                + quote.getSupplier().getName() + (ok ? "" : " (failed)"), officeUser);
        return Change.DONE;
    }

    // Sends the request through the supplier's channel: SENT, or FAILED with the reason. True when it went out.
    private boolean deliver(Rfq rfq, RfqQuote quote) {
        quote.setSentAt(Instant.now());
        SupplierChannel channel = channels.get(quote.getSupplier().getChannel());
        try {
            if (channel == null) {
                throw new SupplierChannel.ChannelException("No channel for " + quote.getSupplier().getChannel(), null);
            }
            channel.sendRfq(rfq, quote.getSupplier());
            quote.setStatus(QuoteStatus.SENT);
            quote.setFailure(null);
            return true;
        } catch (SupplierChannel.ChannelException e) {
            quote.setStatus(QuoteStatus.FAILED);
            quote.setFailure(cut(e.getMessage() == null ? "Not delivered" : e.getMessage(), RfqQuote.REASON_MAX_LENGTH));
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Answers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The office enters a supplier's quote (or corrects it, or enters one after a decline): its currency, delivery
     * cost, delivery date and validity, one line per line offered (a line without a unit price is not offered), and
     * the supplier's PDF when given (in place of an earlier one). QUOTED. Recorded as "Quote entered". CLOSED: the
     * RFQ is no longer OPEN, nothing saved. IllegalArgumentException for a line of another RFQ.
     */
    @Transactional
    public Change enterQuote(User officeUser, Rfq rfq, RfqQuote quote, QuoteEntry entry) {
        checkOffice(officeUser);
        if (!stillOpen(rfq)) {
            return Change.CLOSED;
        }
        Rfq locked = rfq;
        Map<Long, RfqLine> lines = locked.getLines().stream()
                .collect(Collectors.toMap(RfqLine::getId, line -> line));
        clearLines(quote);
        for (LineEntry e : entry.lines()) {
            if (e.unitPrice() == null) {
                continue;
            }
            RfqLine line = lines.get(e.lineId());
            if (line == null) {
                throw new IllegalArgumentException("Line " + e.lineId() + " is not a line of " + locked.number());
            }
            RfqQuoteLine quoteLine = new RfqQuoteLine();
            quoteLine.setQuote(quote);
            quoteLine.setLine(line);
            quoteLine.setUnitPrice(e.unitPrice());
            quoteLine.setQuantityOffered(e.quantityOffered());
            quoteLine.setPackSize(e.packSize());
            quoteLine.setPackUnit(e.packSize() == null ? null : blankToNull(e.packUnit()));
            quoteLine.setNote(blankToNull(e.note()));
            quoteLineRepository.save(quoteLine);
            quote.getLines().add(quoteLine);
        }
        quote.setStatus(QuoteStatus.QUOTED);
        quote.setCurrency(entry.currency().strip().toUpperCase(Locale.ROOT));
        quote.setDeliveryCost(entry.deliveryCost() == null ? BigDecimal.ZERO : entry.deliveryCost());
        quote.setDeliveryDate(entry.deliveryDate());
        quote.setValidUntil(entry.validUntil());
        quote.setDeclineReason(null);
        quote.setFailure(null);
        quote.setAnsweredBy(officeUser);
        quote.setAnsweredAt(Instant.now());
        if (entry.file() != null) {
            String name = entry.file().fileName();
            quote.setFileName(cut(name == null || name.isBlank() ? "quote.pdf" : name.strip(),
                    RfqQuote.FILE_NAME_MAX_LENGTH));
            quote.setFileSize(entry.file().size());
            quote.setFileStoragePath(fileStorage.save(entry.file().content()));
        }
        auditService.record("RFQ_QUOTE_ENTERED", locked.getOrder().getShip(), locked.number() + ", "
                + quote.getSupplier().getName() + ", " + Money.total(quote.getCurrency(), quote.grandTotal(), Locale.ENGLISH)
                + ", " + quote.getLines().size() + " of " + locked.getLines().size() + " lines"
                + (entry.file() != null ? ", " + quote.getFileName() : ""), officeUser);
        return Change.DONE;
    }

    /**
     * The supplier said no: DECLINED with the reason (optional), and any quote it had entered is removed from the
     * comparison (its PDF stays). Undone by entering a quote. Recorded as "Quote declined". CLOSED as enterQuote.
     */
    @Transactional
    public Change decline(User officeUser, Rfq rfq, RfqQuote quote, String reason) {
        checkOffice(officeUser);
        if (!stillOpen(rfq)) {
            return Change.CLOSED;
        }
        Rfq locked = rfq;
        clearLines(quote);
        quote.setStatus(QuoteStatus.DECLINED);
        quote.setDeclineReason(blankToNull(reason) == null ? null : cut(reason.strip(), RfqQuote.REASON_MAX_LENGTH));
        quote.setDeliveryCost(null);
        quote.setDeliveryDate(null);
        quote.setValidUntil(null);
        quote.setAnsweredBy(officeUser);
        quote.setAnsweredAt(Instant.now());
        auditService.record("RFQ_QUOTE_DECLINED", locked.getOrder().getShip(), locked.number() + ", "
                + quote.getSupplier().getName() + (quote.getDeclineReason() == null ? "" : ": " + quote.getDeclineReason()),
                officeUser);
        return Change.DONE;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Closing
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Awards the RFQ to one QUOTED supplier, for all its lines (no split per line): AWARDED, and every other supplier
     * still asked (sent or quoted) gets a short "not selected" message through its channel. The vessel order then
     * shows the award and its Confirm form starts with the RFQ's port and the quote's delivery date. Placing the
     * purchase order is the RfqAwardListener seam (no implementation yet). Recorded as "RFQ awarded".
     * CLOSED: no longer OPEN; NOT_ALLOWED: the quote is not QUOTED.
     */
    @Transactional
    public Change award(User officeUser, Rfq rfq, RfqQuote quote) {
        checkOffice(officeUser);
        if (!stillOpen(rfq)) {
            return Change.CLOSED;
        }
        Rfq locked = rfq;
        if (quote.getStatus() != QuoteStatus.QUOTED || !quote.getRfq().getId().equals(locked.getId())) {
            return Change.NOT_ALLOWED;
        }
        locked.setStatus(RfqStatus.AWARDED);
        locked.setAwardedQuote(quote);
        locked.setClosedBy(officeUser);
        locked.setClosedAt(Instant.now());
        for (RfqQuote other : locked.getQuotes()) {
            if (!other.getId().equals(quote.getId())
                    && (other.getStatus() == QuoteStatus.SENT || other.getStatus() == QuoteStatus.QUOTED)) {
                SupplierChannel channel = channels.get(other.getSupplier().getChannel());
                if (channel != null) {
                    channel.notSelected(locked, other.getSupplier());
                }
            }
        }
        auditService.record("RFQ_AWARDED", locked.getOrder().getShip(), locked.number() + " for order #"
                + locked.getOrder().getId() + " awarded to " + quote.getSupplier().getName() + ", "
                + Money.total(quote.getCurrency(), quote.grandTotal(), Locale.ENGLISH), officeUser);
        awardListeners.orderedStream().forEach(listener -> listener.awarded(locked, quote, officeUser));
        return Change.DONE;
    }

    /**
     * Withdraws an OPEN RFQ: CANCELLED, and each channel tells its suppliers still asked. The order can then get a new
     * RFQ. Recorded as "RFQ cancelled". CLOSED: no longer OPEN.
     */
    @Transactional
    public Change cancel(User officeUser, Rfq rfq) {
        checkOffice(officeUser);
        if (!stillOpen(rfq)) {
            return Change.CLOSED;
        }
        Rfq locked = rfq;
        locked.setStatus(RfqStatus.CANCELLED);
        locked.setClosedBy(officeUser);
        locked.setClosedAt(Instant.now());
        Set<ChannelType> used = locked.getQuotes().stream().map(q -> q.getSupplier().getChannel())
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(ChannelType.class)));
        for (ChannelType type : used) {
            SupplierChannel channel = channels.get(type);
            if (channel != null) {
                channel.cancel(locked);
            }
        }
        auditService.record("RFQ_CANCELLED", locked.getOrder().getShip(), locked.number() + " for order #"
                + locked.getOrder().getId(), officeUser);
        return Change.DONE;
    }

    /** The supplier's PDF: opened in the browser, or downloaded. 404 without one. */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> file(RfqQuote quote, boolean download) {
        if (!quote.hasFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ContentDisposition.Builder disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition
                        .filename(quote.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(fileStorage.load(quote.getFileStoragePath()));
    }

    /** A QuoteFile from an upload. */
    public static QuoteFile file(org.springframework.web.multipart.MultipartFile file) {
        try {
            return new QuoteFile(file.getInputStream(), file.getOriginalFilename(), file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Today in UTC, as every date of the RFQ pages. */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void clearLines(RfqQuote quote) {
        List<RfqQuoteLine> old = new ArrayList<>(quote.getLines());
        quote.getLines().clear();
        quoteLineRepository.deleteAll(old);
        quoteLineRepository.flush();
    }

    // Locks the RFQ's vessel order, then reads the RFQ's status from the database (another office user may have
    // awarded or cancelled it since this request loaded it).
    private boolean stillOpen(Rfq rfq) {
        entityManager.find(ShipOrder.class, rfq.getOrder().getId(), LockModeType.PESSIMISTIC_WRITE);
        RfqStatus status = entityManager.createQuery("select r.status from Rfq r where r.id = :id", RfqStatus.class)
                .setParameter("id", rfq.getId()).getSingleResult();
        return status == RfqStatus.OPEN;
    }

    private static void checkOffice(User officeUser) {
        if (officeUser == null || officeUser.getRole() != Role.OFFICE_USER || !officeUser.isEnabled()) {
            throw new AccessDeniedException("Only the office asks suppliers for quotes");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
