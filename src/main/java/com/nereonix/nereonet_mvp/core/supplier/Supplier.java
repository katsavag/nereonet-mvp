package com.nereonix.nereonet_mvp.core.supplier;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

// A ship chandler or other supplier the office asks for prices (P44). Never deleted: an inactive supplier stays in
// the list and in past RFQs but is not offered for a new one. channel says how an RFQ reaches it (SupplierChannel);
// today always EMAIL.
@Entity
@Getter
@Setter
public class Supplier {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int PORTS_MAX_LENGTH = 500;
    public static final int WEBSITE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    // Where the RFQ emails go.
    @Column(nullable = false, length = EMAIL_MAX_LENGTH)
    private String email;

    // Free text, e.g. "Piraeus, Elefsina, Perama". RFQ suppliers whose text mentions the port are listed first.
    @Column(length = PORTS_MAX_LENGTH)
    private String portsServed;

    // ISO 4217 code, e.g. EUR: the currency its quotes usually come in (a quote can say otherwise).
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = WEBSITE_MAX_LENGTH)
    private String website;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channel = ChannelType.EMAIL;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** True when the ports served mention this port (ignoring case and accents of the simple kind): listed first. */
    public boolean serves(String port) {
        if (port == null || port.isBlank() || portsServed == null) {
            return false;
        }
        return normalize(portsServed).contains(normalize(port));
    }

    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text.strip(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }
}
