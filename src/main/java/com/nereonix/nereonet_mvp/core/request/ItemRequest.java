package com.nereonix.nereonet_mvp.core.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.Setter;

// A request for galley items from a ship (by the Chief cook or a crew member), approved or rejected by the Master.
// The office exports approved requests as CSV.
@Entity
@Getter
@Setter
public class ItemRequest {

    public static final int REJECTION_REASON_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    // Null when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    // Set only when REJECTED.
    @Column(length = REJECTION_REASON_MAX_LENGTH)
    private String rejectionReason;

    // The Master who approved or rejected it. Null while PENDING, or when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User decidedBy;

    // Null while PENDING.
    private Instant decidedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Last CSV export by the office. Null until exported; a request can be exported again.
    private Instant exportedAt;

    @OneToMany(mappedBy = "request")
    @OrderBy("id")
    @BatchSize(size = 50)
    private List<RequestLine> lines = new ArrayList<>();
}
