package com.nereonix.nereonet_mvp.officerequest;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

// The requests checked on the approved requests list.
public record RequestExportForm(@NotEmpty List<Long> ids) {
}
