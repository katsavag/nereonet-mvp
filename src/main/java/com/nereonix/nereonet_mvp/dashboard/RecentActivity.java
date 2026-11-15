package com.nereonix.nereonet_mvp.dashboard;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;

/** The Overview's Recent activity card: the newest audit entries, in one query. */
@Component
class RecentActivity {

    /** The card shows this many entries. */
    static final int SHOWN = 5;

    private final AuditRepository auditRepository;

    RecentActivity(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    List<AuditEntry> newest() {
        return auditRepository.findNewest(PageRequest.of(0, SHOWN));
    }
}
