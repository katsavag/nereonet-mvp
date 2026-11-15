package com.nereonix.nereonet_mvp.navigation;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

// The sidebar counters: one count query each, on every page, so nothing is loaded to count.
@Service
public class NavigationService {

    static final int CERTIFICATE_DAYS = 30;

    private final CertificateRepository certificateRepository;
    private final ItemRequestRepository requestRepository;
    private final ShipOrderRepository orderRepository;
    private final ShipOrderService orderService;
    private final AnnouncementRecipientRepository announcementRecipientRepository;
    private final VettingService vettingService;
    private final CrewProfileRepository profileRepository;
    private final StockService stockService;

    public NavigationService(CertificateRepository certificateRepository, ItemRequestRepository requestRepository,
                             ShipOrderRepository orderRepository, ShipOrderService orderService,
                             AnnouncementRecipientRepository announcementRecipientRepository,
                             VettingService vettingService, CrewProfileRepository profileRepository,
                             StockService stockService) {
        this.stockService = stockService;
        this.profileRepository = profileRepository;
        this.certificateRepository = certificateRepository;
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.announcementRecipientRepository = announcementRecipientRepository;
        this.vettingService = vettingService;
    }

    @Transactional(readOnly = true)
    public NavCounts officeCounts() {
        // UTC days, as the certificate badges and the reminder job. P43: certificates and vessels whose snooze holds
        // are left out.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate until = today.plusDays(CERTIFICATE_DAYS);
        return new NavCounts(certificateRepository.countOpenUntil(until, today, today.plusDays(30), today.plusDays(60),
                        AlertKind.CERTIFICATE_EXPIRED, AlertKind.CERTIFICATE_EXPIRING),
                orderService.countNotReceived(), vettingService.vesselsNeedingAttention());
    }

    /** The Master's counter of Approvals: PENDING requests of the ship of the user with this email. */
    @Transactional(readOnly = true)
    public long approvalsWaiting(String email) {
        return requestRepository.countByStatusOnShipOf(RequestStatus.PENDING, email);
    }

    /**
     * The Master's counter of Vessel order (orders, not lines, since P59): the orders of the ship of the user with
     * this email that need the Master, the OPEN one when it has a line (to send) and the CONFIRMED ones (to receive).
     * SENT orders wait for the office and are not counted.
     */
    @Transactional(readOnly = true)
    public long ordersToAct(String email) {
        return orderRepository.countToActOnShipOf(email, ShipOrderStatus.OPEN, ShipOrderStatus.CONFIRMED);
    }

    /** The Chief cook's counter of Stores: provisions of the ship of the user with this email that are Low or
     *  Out of stock. */
    @Transactional(readOnly = true)
    public long storesBelowThreshold(String email) {
        return stockService.belowThresholdOnShipOf(email);
    }

    /** The Chief cook's and crew's counter of Requests: their own requests still waiting for the Master. */
    @Transactional(readOnly = true)
    public long requestsWaiting(String email) {
        return requestRepository.countByRequestedByEmailAndStatus(email, RequestStatus.PENDING);
    }

    /** The crew's counter of Announcements: the ones the user with this email has not acknowledged yet. */
    @Transactional(readOnly = true)
    public long announcementsToAcknowledge(String email) {
        return announcementRecipientRepository.countByUserEmailAndAcknowledgedAtIsNull(email);
    }

    /** The ship user's own 96px photo URL for the sidebar's user block, or null without a photo. One query. */
    @Transactional(readOnly = true)
    public String photoUrl(Long userId) {
        return profileRepository.findByUserId(userId).map(p -> p.photoUrl(96)).orElse(null);
    }
}
