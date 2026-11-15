package com.nereonix.nereonet_mvp.core.requirement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    // Every requirement with its ranks, in one query (a join on the rank table): what Compliance reads.
    @Query("select distinct r from DocumentRequirement r left join fetch r.ranks order by r.id")
    List<DocumentRequirement> findAllWithRanks();

    boolean existsBySuggestionTrue();

    // P58: the rows from before requirements were per vessel (no vessel; a vessel type or all vessels), with their
    // ranks, oldest first: what VesselRequirementsMigration moves.
    @Query("select distinct r from DocumentRequirement r left join fetch r.ranks where r.ship is null order by r.id")
    List<DocumentRequirement> findLegacyWithRanks();

    // One vessel's requirements with their ranks.
    @Query("select distinct r from DocumentRequirement r left join fetch r.ranks where r.ship.id = :shipId order by r.id")
    List<DocumentRequirement> findAllByShipIdWithRanks(Long shipId);
}
