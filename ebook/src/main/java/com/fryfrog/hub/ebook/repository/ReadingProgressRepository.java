package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.ReadingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, Long> {

    Optional<ReadingProgress> findByEbookId(Long ebookId);
}
