package com.nereonix.nereonet_mvp.core.library;

import java.util.List;

import com.nereonix.nereonet_mvp.core.user.User;

// Who of the crew on board now acknowledged the current version of a Required item, both lists by full name.
public record AcknowledgementOverview(LibraryItem item, List<LibraryAcknowledgement> acknowledged,
                                      List<User> notAcknowledged) {
}
