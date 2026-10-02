package rw.bank.ibanking.onboarding.service;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.StaffRepository;

/**
 * Loads a member of bank staff for authentication.
 *
 * <p>The role becomes a {@code ROLE_ADMIN} or {@code ROLE_MANAGER} authority, which is what
 * the method-level checks on the controller switch on. Keeping the mapping here means there
 * is exactly one place where a staff row becomes a set of permissions.
 */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffRepository staff;

    StaffUserDetailsService(StaffRepository staff) {
        this.staff = staff;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        StaffEntity member =
                staff.findByEmailIgnoreCase(email)
                        .orElseThrow(
                                // The message reaches a log, never a client: the entry
                                // point returns the standard envelope with no detail.
                                () -> new UsernameNotFoundException("No staff member for " + email));

        return User.withUsername(member.email())
                .password(member.passwordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())))
                .build();
    }

    /** The staff row behind an authenticated principal. */
    public StaffEntity require(String email) {
        return staff.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No staff member for " + email));
    }
}
