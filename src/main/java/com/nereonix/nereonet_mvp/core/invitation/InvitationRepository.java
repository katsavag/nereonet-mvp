package com.nereonix.nereonet_mvp.core.invitation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    List<Invitation> findAllByStatusAndRoleOrderByFullNameAsc(Invitation.Status status, Role role);

    List<Invitation> findAllByStatusAndRoleNotOrderByFullNameAsc(Invitation.Status status, Role role);

    List<Invitation> findAllByStatusAndShipOrderByFullNameAsc(Invitation.Status status, Ship ship);

    long countByStatusAndShip(Invitation.Status status, Ship ship);

    Optional<Invitation> findByTokenId(UUID tokenId);

    void deleteByEmailAndStatus(String email, Invitation.Status status);

    void deleteByEmail(String email);

    @Modifying
    @Query("update Invitation i set i.invitedBy = null where i.invitedBy = :user")
    void clearInvitedBy(User user);
}
