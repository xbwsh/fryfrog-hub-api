package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicPlayStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MusicPlayStatRepository extends JpaRepository<MusicPlayStat, Long> {

    Optional<MusicPlayStat> findBySong_IdAndUserId(Long songId, Long userId);

    List<MusicPlayStat> findByUserIdOrderByLastPlayedAtDesc(Long userId);

    List<MusicPlayStat> findBySong_IdIn(Collection<Long> songIds);

    List<MusicPlayStat> findTop10BySong_LibraryIdInOrderByLastPlayedAtDesc(Collection<Long> libraryIds);

    List<MusicPlayStat> findTop10ByUserIdAndSong_LibraryIdInOrderByLastPlayedAtDesc(Long userId, Collection<Long> libraryIds);

    List<MusicPlayStat> findTop10BySong_LibraryIdInOrderByPlayCountDesc(Collection<Long> libraryIds);

    List<MusicPlayStat> findTop10ByUserIdAndSong_LibraryIdInOrderByPlayCountDesc(Long userId, Collection<Long> libraryIds);

    void deleteBySong_Id(Long songId);

    void deleteBySong_IdIn(Collection<Long> songIds);
}
