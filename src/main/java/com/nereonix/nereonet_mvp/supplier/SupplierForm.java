package com.nereonix.nereonet_mvp.supplier;

import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierService;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Add supplier and Edit supplier (P44): name, contact email, ports served (free text), currency, website, active.
public class SupplierForm {

    @NotBlank(message = "{supplier.error.nameRequired}")
    @Size(max = Supplier.NAME_MAX_LENGTH, message = "{supplier.error.tooLong}")
    private String name;

    @NotBlank(message = "{supplier.error.emailRequired}")
    @Email(message = "{supplier.error.emailInvalid}")
    @Size(max = Supplier.EMAIL_MAX_LENGTH, message = "{supplier.error.tooLong}")
    private String email;

    @Size(max = Supplier.PORTS_MAX_LENGTH, message = "{supplier.error.tooLong}")
    private String portsServed;

    @NotBlank(message = "{supplier.error.currencyRequired}")
    @Pattern(regexp = "^\\s*[A-Za-z]{3}\\s*$", message = "{supplier.error.currencyInvalid}")
    private String currency = "EUR";

    @Size(max = Supplier.WEBSITE_MAX_LENGTH, message = "{supplier.error.tooLong}")
    @Pattern(regexp = "^\\s*$|^\\s*https?://\\S+\\s*$", message = "{supplier.error.websiteInvalid}")
    private String website;

    private boolean active = true;

    static SupplierForm of(Supplier supplier) {
        SupplierForm form = new SupplierForm();
        form.setName(supplier.getName());
        form.setEmail(supplier.getEmail());
        form.setPortsServed(supplier.getPortsServed());
        form.setCurrency(supplier.getCurrency());
        form.setWebsite(supplier.getWebsite());
        form.setActive(supplier.isActive());
        return form;
    }

    SupplierService.Details details() {
        return new SupplierService.Details(name, email, portsServed, currency, website, active);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? null : email.strip(); }
    public String getPortsServed() { return portsServed; }
    public void setPortsServed(String portsServed) { this.portsServed = portsServed; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
