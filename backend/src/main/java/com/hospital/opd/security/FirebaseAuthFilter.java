package com.hospital.opd.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the Firebase ID token on every request (web today, Android later: same header).
 * The role comes ONLY from the signed token's custom claim `role`, which is set server-side with the Admin SDK.
 * Nothing the client sends in a body or header can grant a role.
 */
public class FirebaseAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                FirebaseToken t = FirebaseAuth.getInstance().verifyIdToken(h.substring(7));
                String role = String.valueOf(t.getClaims().getOrDefault("role", "PATIENT"));
                String doctorId = (String) t.getClaims().get("doctorId");
                var auth = new UsernamePasswordAuthenticationToken(new AppUser(t.getUid(), role, doctorId), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
