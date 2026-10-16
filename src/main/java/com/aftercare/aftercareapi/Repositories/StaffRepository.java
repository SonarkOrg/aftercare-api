package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.*;

public interface StaffRepository extends JpaRepository<StaffMember, Long> {

    Optional<StaffMember> findFirstByAuth0SubjectAndActiveTrue(String auth0Subject);

    Optional<StaffMember> findByEmailIgnoreCase(String email);

    Optional<StaffMember> findByAuth0Subject(String auth0Subject);

    List<StaffMember> findByActiveTrue();

    /** Active administrators who can actually log in (linked to an auth0 subject). */
    long countByAccessRoleAndActiveTrueAndAuth0SubjectIsNotNull(AccessRole accessRole);

    /** Row-locked read used by the last-admin guard, so two concurrent demotions cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<StaffMember> findByAccessRoleAndActiveTrueAndAuth0SubjectIsNotNull(AccessRole accessRole);
}
