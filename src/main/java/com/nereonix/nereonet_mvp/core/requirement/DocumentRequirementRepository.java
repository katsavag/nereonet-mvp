package com.nereonix.nereonet_mvp.core.requirement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    // Every requirement with its ranks, in one query (a join on the rank table): what Compliance reads.
    @Query("select distinct r from DocumentRequirement r left join fetch r.ranks order by r.id")
    List<DocumentRequirement> findAllWithRanks();

    boolean existsBySuggestionTrue();
}
