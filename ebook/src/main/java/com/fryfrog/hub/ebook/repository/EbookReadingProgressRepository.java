package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EbookReadingProgressRepository extends JpaRepository<EbookReadingProgress, Long> {

    Optional<EbookReadingProgress> findByEbookId(Long ebookId);

    List<EbookReadingProgress> findAllByOrderByUpdatedAtDesc();

    long countByCompletedTrue();
}
