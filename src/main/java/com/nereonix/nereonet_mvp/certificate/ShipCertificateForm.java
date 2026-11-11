package com.nereonix.nereonet_mvp.certificate;

import jakarta.validation.constraints.NotNull;

// Add certificate from the ship page's Certificates tab: the certificate form plus the crew member, picked from
// the crew of that ship only.
public class ShipCertificateForm extends CertificateForm {

    @NotNull(message = "{certificate.error.crewMemberRequired}")
    private Long userId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
