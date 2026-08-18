package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicArtist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MusicArtistRepository extends JpaRepository<MusicArtist, Long> {

    Optional<MusicArtist> findFirstByNameAndLibraryId(String name, Long libraryId);

    List<MusicArtist> findByLibraryIdInOrderByNameAsc(Collection<Long> libraryIds);

    long countByLibraryIdIn(Collection<Long> libraryIds);

    List<MusicArtist> findByLibraryIdInAndNameContainingIgnoreCase(Collection<Long> libraryIds, String query);
}
