package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.BookSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookSourceRepository extends JpaRepository<BookSource, Long> {

    List<BookSource> findByEnabledTrueAndDeletedFalseOrderBySortOrderAsc();

    List<BookSource> findByDeletedFalseOrderBySortOrderAsc();

    List<BookSource> findByGroupAndDeletedFalse(String group);

    List<BookSource> findBySourceTypeAndDeletedFalse(String sourceType);

    Optional<BookSource> findByIdAndDeletedFalse(Long id);

    @Query("SELECT bs FROM BookSource bs WHERE bs.enabled = true AND bs.deleted = false " +
           "AND (bs.name LIKE %:keyword% OR bs.group LIKE %:keyword%) " +
           "ORDER BY bs.sortOrder ASC")
    List<BookSource> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT DISTINCT bs.group FROM BookSource bs WHERE bs.deleted = false AND bs.group IS NOT NULL")
    List<String> findDistinctGroups();

    @Query("SELECT COUNT(bs) FROM BookSource bs WHERE bs.deleted = false AND bs.enabled = true")
    long countEnabled();

    boolean existsByUrlAndDeletedFalse(String url);
}
