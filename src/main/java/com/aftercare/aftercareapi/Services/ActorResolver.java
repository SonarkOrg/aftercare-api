package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Repositories.StaffRepository;
import com.aftercare.aftercareapi.config.security.StaffPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Service;

/** Resolves the acting staff member's display name: staff directory first, then the JWT {@code name} claim. */
@Service
public class ActorResolver {

    private final StaffRepository staffRepository;

    public ActorResolver(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    public Actor from(Jwt jwt) {
        // On staff requests the staff filter has already resolved the directory entry (always present there).
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(StaffPrincipal.ATTRIBUTE) instanceof StaffPrincipal staff
                && staff.sub().equals(jwt.getSubject())) {
            return new Actor(staff.sub(), staff.displayName());
        }
        String sub = jwt.getSubject() == null ? "unknown" : jwt.getSubject();
        String name = nameForSub(sub);
        if (name == null) {
            String claim = jwt.getClaimAsString("name");
            if (claim != null && !claim.isBlank()) {
                name = claim.trim();
                if (name.length() > 255) {
                    name = name.substring(0, 255);
                }
            }
        }
        return new Actor(sub, name);
    }

    /** Display name of the active staff member linked to this subject, else null. */
    public String nameForSub(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        return staffRepository.findFirstByAuth0SubjectAndActiveTrue(sub).map(s -> s.getDisplayName()).orElse(null);
    }
}
