package com.nereonix.nereonet_mvp.item;

import com.nereonix.nereonet_mvp.core.importfile.ImportForm;

// An item import in progress for one ship (P24): the uploaded file with its mapping, then every item from it on the
// review screen. Kept in the HTTP session until the import is done or cancelled (see ItemImportController).
public class ItemImportForm extends ImportForm<ItemImportRow> {

    // The ship the file was uploaded for. Null when there is no import in the session.
    private Long shipId;

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }
}
