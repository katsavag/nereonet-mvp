package com.nereonix.nereonet_mvp.core.vetting;

/** The kind of a SIRE 2.0 negative observation (P41): about equipment, a procedure, or a person. */
public enum ObservationType {
    HARDWARE, PROCESS, HUMAN;

    public String messageKey() {
        return "sire.type." + name();
    }
}
