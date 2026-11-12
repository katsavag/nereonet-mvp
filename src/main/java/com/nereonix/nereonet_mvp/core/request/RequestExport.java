package com.nereonix.nereonet_mvp.core.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// One CSV export of approved requests by the office (P21): who, when and which requests. Its file can be downloaded
// again (GET /office/requests/exports/{id}) and the list can show exactly its requests. The request ids are plain
// values, not foreign keys: a request that no longer exists is simply left out of the file.
@Entity
@Getter
@Setter
public class RequestExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Null when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @ElementCollection
    @CollectionTable(name = "request_export_request", joinColumns = @JoinColumn(name = "export_id"))
    @Column(name = "request_id", nullable = false)
    private List<Long> requestIds = new ArrayList<>();
}
