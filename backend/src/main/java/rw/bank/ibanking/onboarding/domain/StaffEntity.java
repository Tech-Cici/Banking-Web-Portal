package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A member of bank staff: an administrator or a manager, never both. */
@Entity
@Table(name = "staff")
public class StaffEntity {

    @Id private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffRole role;

    @Column(nullable = false)
    private String branch;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    protected StaffEntity() {
        // JPA.
    }

    public StaffEntity(
            String fullName, String email, StaffRole role, String branch, String passwordHash) {
        this.id = UUID.randomUUID();
        this.fullName = fullName;
        this.email = email;
        this.role = role;
        this.branch = branch;
        this.passwordHash = passwordHash;
    }

    public UUID id() {
        return id;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public StaffRole role() {
        return role;
    }

    public String branch() {
        return branch;
    }

    public String passwordHash() {
        return passwordHash;
    }
}
