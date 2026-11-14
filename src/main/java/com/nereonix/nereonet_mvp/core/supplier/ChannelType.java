package com.nereonix.nereonet_mvp.core.supplier;

// How an RFQ reaches a supplier (P44): the SupplierChannel implementation whose type() it is. Only EMAIL today; a
// marketplace or API channel would add a value here and a SupplierChannel bean, and no screen changes.
// Labels: supplier.channel.<NAME>.
public enum ChannelType {
    EMAIL
}
