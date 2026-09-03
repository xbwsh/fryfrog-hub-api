package com.fryfrog.hub.audiobook.repository;

import com.fryfrog.hub.audiobook.model.Audiobook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AudiobookRepository extends JpaRepository<Audiobook, Long> {

    Optional<Audiobook> findByBookPath(String bookPath);

    List<Audiobook> findByLibraryId(Long libraryId);

    Page<Audiobook> findByLibraryIdIn(Collection<Long> libraryIds, Pageable pageable);

    Page<Audiobook> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String q, Pageable pageable);

    Page<Audiobook> findByLibraryIdInAndAuthorIgnoreCase(Collection<Long> libraryIds, String author, Pageable pageable);

    @Query("SELECT a.author, COUNT(a) FROM Audiobook a " +
           "WHERE a.libraryId IN :libraryIds AND a.author IS NOT NULL AND a.author <> '' " +
           "GROUP BY a.author ORDER BY COUNT(a) DESC, a.author")
    Page<Object[]> countByAuthor(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    long countByLibraryId(Long libraryId);
}
