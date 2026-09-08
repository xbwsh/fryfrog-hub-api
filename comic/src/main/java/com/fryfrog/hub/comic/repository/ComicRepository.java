package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.Comic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ComicRepository extends JpaRepository<Comic, Long> {

    List<Comic> findByLibraryId(Long libraryId);

    Page<Comic> findByLibraryIdIn(Collection<Long> libraryIds, Pageable pageable);

    Page<Comic> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String q, Pageable pageable);
}
