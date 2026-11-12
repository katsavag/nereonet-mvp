package com.nereonix.nereonet_mvp.core.profile;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewProfileRepository extends JpaRepository<CrewProfile, Long> {

    Optional<CrewProfile> findByUserId(Long userId);
}
