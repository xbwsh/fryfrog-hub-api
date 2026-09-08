package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.EbookProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EbookProgressRepository extends JpaRepository<EbookProgress, Long> {

    @EntityGraph(attributePaths = "ebook")
    Optional<EbookProgress> findByUserIdAndEbook_Id(Long userId, Long ebookId);

    List<EbookProgress> findByUserIdAndEbook_IdIn(Long userId, Collection<Long> ebookIds);

    void deleteByUserIdAndEbook_Id(Long userId, Long ebookId);

    void deleteByEbook_Id(Long ebookId);
}
