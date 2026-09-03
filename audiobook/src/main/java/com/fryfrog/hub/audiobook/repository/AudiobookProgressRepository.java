package com.fryfrog.hub.audiobook.repository;

import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AudiobookProgressRepository extends JpaRepository<AudiobookProgress, Long> {

    @EntityGraph(attributePaths = "audiobook")
    Optional<AudiobookProgress> findByUserIdAndAudiobook_Id(Long userId, Long audiobookId);

    List<AudiobookProgress> findByUserIdAndAudiobook_IdIn(Long userId, Collection<Long> audiobookIds);

    void deleteByUserIdAndAudiobook_Id(Long userId, Long audiobookId);
}
