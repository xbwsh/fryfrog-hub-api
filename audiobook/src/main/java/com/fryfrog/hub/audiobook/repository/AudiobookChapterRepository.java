package com.fryfrog.hub.audiobook.repository;

import com.fryfrog.hub.audiobook.model.AudiobookChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AudiobookChapterRepository extends JpaRepository<AudiobookChapter, Long> {

    List<AudiobookChapter> findByAudiobook_IdOrderByChapterIndexAsc(Long audiobookId);

    void deleteByAudiobook_Id(Long audiobookId);
}
