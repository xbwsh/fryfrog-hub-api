package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.Ebook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EbookRepository extends JpaRepository<Ebook, Long> {

    List<Ebook> findByLibraryId(Long libraryId);

    Page<Ebook> findByLibraryIdIn(Collection<Long> libraryIds, Pageable pageable);

    Page<Ebook> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String q, Pageable pageable);

    Page<Ebook> findByLibraryIdInAndAuthorIgnoreCase(Collection<Long> libraryIds, String author, Pageable pageable);

    @Query("SELECT e.author, COUNT(e) FROM Ebook e " +
           "WHERE e.libraryId IN :libraryIds AND e.author IS NOT NULL AND e.author <> '' " +
           "GROUP BY e.author ORDER BY COUNT(e) DESC, e.author")
    Page<Object[]> countByAuthor(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);
}
