package com.demo.authservice.user.entities;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** The two columns the revocation feed serves, and nothing else of the row. */
    record TokenVersion(Long id, int tokenVersion) {}

    /**
     * The caller's own edit, as one statement. Conditional on the hash the caller was just checked against, so
     * a password changed in between makes this a no-op (0 rows) rather than a silent overwrite; and the version
     * bump is relative, so a revoke that lands in between is kept rather than written back stale.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update User u set u.name = :name, u.email = :email, u.passwordHash = :hash, "
            + "u.tokenVersion = u.tokenVersion + :bump where u.id = :id and u.passwordHash = :expectedHash")
    int edit(Long id, String name, String email, String expectedHash, String hash, int bump);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<TokenVersion> findByTokenVersionGreaterThan(int version);
}
