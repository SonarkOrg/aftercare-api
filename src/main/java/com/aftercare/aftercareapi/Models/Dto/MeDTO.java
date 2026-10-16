package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.Permission;
import com.aftercare.aftercareapi.Enums.StaffRole;

import java.util.List;

/** The caller's own staff identity. {@code role} is the profession; {@code accessRole} drives {@code permissions}. {@code uiLanguage} is null until the person chooses (shown as French). */
public record MeDTO(Long staffId, String sub, String displayName, String email, StaffRole role, AccessRole accessRole,
                    List<Permission> permissions, Language uiLanguage) {
}
