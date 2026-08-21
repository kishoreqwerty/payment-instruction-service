package com.kishore.payments.exception.api;

import java.security.Principal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Added building the ops-dashboard (Phase 9, see PHASE-9-REPORT.md §5): the
 * brief requires the UI to hide actions a role can't take (§5, "the UI
 * reflects the role"), and HTTP Basic gives the browser no way to learn the
 * authenticated user's roles on its own -- unlike a JWT, a base64 pair on
 * every request carries no claims for the client to read. Without this, the
 * dashboard's only options were hardcoding a username -> role table (wrong
 * the moment a user is added or renamed in {@code SecurityConfig}) or asking
 * the operator to also pick their role at login (redundant with the
 * authentication they just did, and one more way for the UI's belief about
 * the role to drift from the server's). A endpoint that echoes back what the
 * security context already resolved is the smaller change.
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

    @GetMapping
    public MeResponse me(Principal principal, Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority).toList();
        return new MeResponse(principal.getName(), roles);
    }

    public record MeResponse(String username, List<String> roles) {
    }
}
