package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.Ebook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EbookRepository extends JpaRepository<Ebook, Long> {

    List<Ebook> findByTitleContainingIgnoreCase(String title);

    List<Ebook> findByAuthorContainingIgnoreCase(String author);

    List<Ebook> findByGenreContainingIgnoreCase(String genre);

    Optional<Ebook> findByFilePath(String filePath);

    List<Ebook> findByFavoriteTrue();
}
