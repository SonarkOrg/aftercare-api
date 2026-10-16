package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.Permission;
import com.aftercare.aftercareapi.Services.StaffAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs after bearer authentication on the staff chain. Looks the JWT subject up in the staff directory on every request
 * and replaces the authentication with one carrying STAFF, ROLE_x and PERM_x authorities. A login without an active
 * staff member keeps no authorities, so every guarded route answers NOT_STAFF. Roles are never read from token claims.
 */
public class StaffContextFilter extends OncePerRequestFilter {

    private final StaffAccessService staffAccess;

    public StaffContextFilter(StaffAccessService staffAccess) {
        this.staffAccess = staffAccess;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            Jwt jwt = token.getToken();
            Optional<StaffPrincipal> staff = staffAccess.resolve(jwt);
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (staff.isPresent()) {
                StaffPrincipal p = staff.get();
                authorities.add(new SimpleGrantedAuthority(CurrentStaff.STAFF_AUTHORITY));
                authorities.add(new SimpleGrantedAuthority("ROLE_" + p.accessRole().name()));
                for (Permission permission : RolePermissions.ordered(p.accessRole())) {
                    authorities.add(new SimpleGrantedAuthority(CurrentStaff.PERMISSION_PREFIX + permission.name()));
                }
                request.setAttribute(StaffPrincipal.ATTRIBUTE, p);
            }
            JwtAuthenticationToken replaced = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
            replaced.setDetails(token.getDetails());
            SecurityContextHolder.getContext().setAuthentication(replaced);
        }
        chain.doFilter(request, response);
    }
}
