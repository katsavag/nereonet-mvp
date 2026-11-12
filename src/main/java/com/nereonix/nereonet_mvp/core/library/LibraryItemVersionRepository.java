package com.nereonix.nereonet_mvp.core.library;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryItemVersionRepository extends JpaRepository<LibraryItemVersion, Long> {

    /** Every version of the item, newest first: its version history (P22). */
    List<LibraryItemVersion> findAllByItemOrderByPublishedAtDescIdDesc(LibraryItem item);
}
