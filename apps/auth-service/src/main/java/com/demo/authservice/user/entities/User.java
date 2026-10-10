package com.demo.authservice.user.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A registered user. The plain password is never stored, only its BCrypt hash. */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    /**
     * Stored as its name rather than its ordinal, so reordering the enum cannot silently re-grade everybody.
     * The DDL default is what lets this column be added to a table that already has rows in it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'USER'")
    private Role role = Role.USER;

    /** A suspended user cannot log in, and every token it already holds stops working. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean suspended;

    /**
     * Stamped into every token as "ver". Bumping it kills every token issued before, in both services, which
     * is how a signed token that cannot be recalled gets revoked anyway. A counter rather than a timestamp, so
     * a token minted in the same second as the revocation is never mistaken for one minted before it.
     */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int tokenVersion;

    /** A new user is always a USER; nothing but an admin's say-so moves it off that. */
    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = Role.USER;
    }
}
