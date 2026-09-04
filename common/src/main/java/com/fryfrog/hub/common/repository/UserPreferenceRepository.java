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
     *
     * 注意：pg_advisory_xact_lock 的返回值会被 JDBC 驱动包装为 PGobject，
     * 映射为 Long 会抛 ClassCastException，因此外层套 NULL::bigint 转换，
     * 让方法永远返回 null（锁的持有与否不依赖返回值，事务结束即释放）。
     */
    @Query(value = "SELECT NULL FROM (SELECT pg_advisory_xact_lock(:userId)) t", nativeQuery = true)
    Long lockUserPreferences(@Param("userId") Long userId);
}
