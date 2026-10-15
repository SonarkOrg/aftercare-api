package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface StaffRepository extends JpaRepository<StaffMember, Long> {

    Optional<StaffMember> findFirstByAuth0SubjectAndActiveTrue(String auth0Subject);

    Optional<StaffMember> findByEmailIgnoreCase(String email);

    Optional<StaffMember> findByAuth0Subject(String auth0Subject);

    List<StaffMember> findByActiveTrue();
}
