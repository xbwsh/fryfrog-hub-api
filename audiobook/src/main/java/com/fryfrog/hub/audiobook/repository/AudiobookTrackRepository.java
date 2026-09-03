package com.fryfrog.hub.audiobook.repository;

import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface AudiobookTrackRepository extends JpaRepository<AudiobookTrack, Long> {

    @EntityGraph(attributePaths = "audiobook")
    Optional<AudiobookTrack> findWithAudiobookById(Long id);

    List<AudiobookTrack> findByAudiobook_IdOrderByTrackIndexAsc(Long audiobookId);

    void deleteByAudiobook_Id(Long audiobookId);
}
