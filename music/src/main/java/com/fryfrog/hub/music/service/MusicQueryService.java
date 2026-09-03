package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音乐内容查询：所有查询按当前请求用户可见媒体库（{@link MediaLibraryService#getAllowableLibraryIds()}）过滤。
 */
@Service
@RequiredArgsConstructor
public class MusicQueryService {

    private final MusicArtistRepository artistRepository;
    private final MusicAlbumRepository albumRepository;
    private final MusicSongRepository songRepository;
    private final MediaLibraryService mediaLibraryService;

    public Collection<Long> allowedLibraryIds() {
        return mediaLibraryService.getAllowableLibraryIds();
    }

    // ── 歌手 ──

    public List<MusicArtist> getAllArtists() {
        return artistRepository.findByLibraryIdInOrderByNameAsc(allowedLibraryIds());
    }

    public Page<MusicArtist> getArtistsPage(Pageable pageable) {
        return artistRepository.findByLibraryIdIn(allowedLibraryIds(), pageable);
    }

    public List<MusicArtist> searchArtists(String query, int limit) {
        return artistRepository.findByLibraryIdInAndNameContainingIgnoreCase(allowedLibraryIds(), query)
                .stream().limit(limit).toList();
    }

    public MusicArtist getArtist(Long id) {
        return artistRepository.findById(id).filter(a -> isVisible(a.getLibraryId())).orElse(null);
    }

    public List<MusicAlbum> getAlbumsByArtist(Long artistId) {
        return albumRepository.findByArtist_IdOrderByYearAsc(artistId).stream()
                .filter(a -> isVisible(a.getLibraryId())).toList();
    }

    // ── 专辑 ──

    public List<MusicAlbum> getAllAlbums() {
        return albumRepository.findByLibraryIdInOrderByTitleAsc(allowedLibraryIds());
    }

    public Page<MusicAlbum> getAlbumsPage(Pageable pageable) {
        return albumRepository.findByLibraryIdIn(allowedLibraryIds(), pageable);
    }

    /** 指定歌手在可见库内的专辑数（批量，一次查询）。 */
    public Map<Long, Long> albumCountByArtistIds(Collection<Long> artistIds) {
        Map<Long, Long> map = new LinkedHashMap<>();
        if (artistIds == null || artistIds.isEmpty()) return map;
        for (Map<String, Object> row : albumRepository.countByArtistIds(artistIds, allowedLibraryIds())) {
            Object id = row.get("artistId");
            Object cnt = row.get("cnt");
            if (id instanceof Number n && cnt instanceof Number c) {
                map.put(n.longValue(), c.longValue());
            }
        }
        return map;
    }

    public List<MusicAlbum> searchAlbums(String query, int limit) {
        var byTitle = albumRepository.findByLibraryIdInAndTitleContainingIgnoreCase(allowedLibraryIds(), query);
        var byArtist = albumRepository.findByLibraryIdInAndArtistNameContainingIgnoreCase(allowedLibraryIds(), query);
        Map<Long, MusicAlbum> dedup = new LinkedHashMap<>();
        byTitle.forEach(a -> dedup.put(a.getId(), a));
        byArtist.forEach(a -> dedup.putIfAbsent(a.getId(), a));
        return dedup.values().stream().limit(limit).toList();
    }

    public MusicAlbum getAlbum(Long id) {
        return albumRepository.findById(id).filter(a -> isVisible(a.getLibraryId())).orElse(null);
    }

    public List<MusicSong> getSongsByAlbum(Long albumId) {
        return songRepository.findByAlbum_IdOrderByDiscNumberAscTrackNumberAsc(albumId).stream()
                .filter(s -> isVisible(s.getLibraryId())).toList();
    }

    // ── 单曲 ──

    public List<MusicSong> getAllSongs(int limit) {
        return songRepository.findByLibraryIdIn(allowedLibraryIds(), PageRequest.of(0, limit));
    }

    public List<MusicSong> searchSongs(String query, int limit) {
        var byTitle = songRepository.findByLibraryIdInAndTitleContainingIgnoreCase(allowedLibraryIds(), query);
        var byArtist = songRepository.findByLibraryIdInAndArtistNameContainingIgnoreCase(allowedLibraryIds(), query);
        var byAlbum = songRepository.findByLibraryIdInAndAlbumNameContainingIgnoreCase(allowedLibraryIds(), query);
        Map<Long, MusicSong> dedup = new LinkedHashMap<>();
        byTitle.forEach(s -> dedup.put(s.getId(), s));
        byArtist.forEach(s -> dedup.putIfAbsent(s.getId(), s));
        byAlbum.forEach(s -> dedup.putIfAbsent(s.getId(), s));
        return dedup.values().stream().limit(limit).toList();
    }

    public MusicSong getSong(Long id) {
        return songRepository.findById(id).filter(s -> isVisible(s.getLibraryId())).orElse(null);
    }

    public Page<MusicSong> getSongsPage(Pageable pageable) {
        return songRepository.findPageByLibraryIdIn(allowedLibraryIds(), pageable);
    }

    public Page<MusicSong> searchSongsPage(String query, Pageable pageable) {
        // 三个字段合并去重后内存分页：title/artist/album 三路 JPQL 各自分页无法保证全局去重正确
        var byTitle = songRepository.findByLibraryIdInAndTitleContainingIgnoreCase(allowedLibraryIds(), query);
        var byArtist = songRepository.findByLibraryIdInAndArtistNameContainingIgnoreCase(allowedLibraryIds(), query);
        var byAlbum = songRepository.findByLibraryIdInAndAlbumNameContainingIgnoreCase(allowedLibraryIds(), query);
        Map<Long, MusicSong> dedup = new LinkedHashMap<>();
        byTitle.forEach(s -> dedup.put(s.getId(), s));
        byArtist.forEach(s -> dedup.putIfAbsent(s.getId(), s));
        byAlbum.forEach(s -> dedup.putIfAbsent(s.getId(), s));
        List<MusicSong> all = List.copyOf(dedup.values());
        int total = all.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        return new org.springframework.data.domain.PageImpl<>(all.subList(start, end), pageable, total);
    }

    public Page<MusicSong> getSongsByGenrePage(String genre, Pageable pageable) {
        return songRepository.findPageByLibraryIdInAndGenreIgnoreCase(allowedLibraryIds(), genre, pageable);
    }

    // ── 流派 ──

    public List<String> getAllGenres() {
        return songRepository.findByLibraryIdIn(allowedLibraryIds()).stream()
                .map(MusicSong::getGenre)
                .filter(g -> g != null && !g.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public List<MusicSong> getSongsByGenre(String genre, int limit) {
        return songRepository.findByLibraryIdInAndGenreIgnoreCase(allowedLibraryIds(), genre)
                .stream().limit(limit).toList();
    }

    // ── 可见性 ──

    public boolean isVisible(Long libraryId) {
        return libraryId != null && allowedLibraryIds().contains(libraryId);
    }

    public boolean isSongVisible(Long songId) {
        MusicSong song = songRepository.findById(songId).orElse(null);
        return song != null && isVisible(song.getLibraryId());
    }
}
