package com.nereonix.nereonet_mvp.core.importfile;

import java.io.Serializable;

// The counts shown on the Done step (P24). skipped: excluded rows and rows that change nothing.
public record ImportResult(int created, int updated, int skipped) implements Serializable {
}
