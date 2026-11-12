package com.nereonix.nereonet_mvp.navigation;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;

// The sidebar counters: one count query each, on every page, so nothing is loaded to count.
@Service
public class NavigationService {

    static final int CERTIFICATE_DAYS = 30;

    private final CertificateRepository certificateRepository;
    private final ItemRequestRepository requestRepository;

    public NavigationService(CertificateRepository certificateRepository, ItemRequestRepository requestRepository) {
        this.certificateRepository = certificateRepository;
        this.requestRepository = requestRepository;
    }

    @Transactional(readOnly = true)
    public NavCounts officeCounts() {
        // UTC days, as the certificate badges and the reminder job.
        LocalDate until = LocalDate.now(ZoneOffset.UTC).plusDays(CERTIFICATE_DAYS);
        return new NavCounts(certificateRepository.countByExpiresOnLessThanEqual(until),
                requestRepository.countByStatusAndExportedAtIsNull(RequestStatus.APPROVED));
    }

    /** The Master's counter of Approvals (P33): PENDING requests of the ship of the user with this email. */
    @Transactional(readOnly = true)
    public long approvalsWaiting(String email) {
        return requestRepository.countByStatusOnShipOf(RequestStatus.PENDING, email);
    }
}
