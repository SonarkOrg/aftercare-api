package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface DocumentBlobRepository extends JpaRepository<DocumentBlob, String> {
}
