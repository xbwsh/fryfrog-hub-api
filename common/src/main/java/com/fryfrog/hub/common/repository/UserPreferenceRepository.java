package com.fryfrog.hub.common.repository;

import com.fryfrog.hub.common.model.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByUserId(Long userId);

    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);

    void deleteByUserId(Long userId);

    /**
     * Serialize full preference replacements for the same user. The lock is
     * held until the surrounding transaction commits or rolls back.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:userId)", nativeQuery = true)
    Long lockUserPreferences(@Param("userId") Long userId);
}
