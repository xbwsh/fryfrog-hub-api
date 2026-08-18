package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicPlaylist;
import com.fryfrog.hub.music.model.MusicPlaylistEntry;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicPlaylistEntryRepository;
import com.fryfrog.hub.music.repository.MusicPlaylistRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放列表：按用户隔离，仅属主可改；公开列表其他人可读。
 */
@Service
@RequiredArgsConstructor
public class MusicPlaylistService {

    private final MusicPlaylistRepository playlistRepository;
    private final MusicPlaylistEntryRepository entryRepository;
    private final MusicSongRepository songRepository;

    public List<MusicPlaylist> getPlaylists(long userId) {
        List<MusicPlaylist> mine = playlistRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<MusicPlaylist> publics = playlistRepository.findByIsPublicTrueOrderByCreatedAtAsc();
        java.util.LinkedHashMap<Long, MusicPlaylist> dedup = new java.util.LinkedHashMap<>();
        mine.forEach(p -> dedup.put(p.getId(), p));
        publics.forEach(p -> dedup.putIfAbsent(p.getId(), p));
        return new ArrayList<>(dedup.values());
    }

    public MusicPlaylist getPlaylist(Long id) {
        return playlistRepository.findById(id).orElse(null);
    }

    /** 显式查询条目（避免复用持久化上下文中已初始化但过期的懒加载集合）。 */
    public List<MusicPlaylistEntry> getEntries(Long playlistId) {
        return entryRepository.findByPlaylist_IdOrderByPositionAsc(playlistId);
    }

    public boolean canRead(MusicPlaylist playlist, long userId) {
        return playlist != null && (Boolean.TRUE.equals(playlist.getIsPublic()) || playlist.getUserId().equals(userId));
    }

    public boolean canWrite(MusicPlaylist playlist, long userId) {
        return playlist != null && playlist.getUserId().equals(userId);
    }

    @Transactional
    public MusicPlaylist createPlaylist(long userId, String name, String comment, boolean isPublic, List<Long> songIds) {
        MusicPlaylist playlist = new MusicPlaylist();
        playlist.setName(name);
        playlist.setUserId(userId);
        playlist.setComment(comment);
        playlist.setIsPublic(isPublic);
        playlistRepository.save(playlist);
        replaceSongs(playlist, songIds);
        return playlist;
    }

    @Transactional
    public MusicPlaylist updatePlaylist(Long id, String name, String comment, Boolean isPublic) {
        MusicPlaylist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new com.fryfrog.hub.common.exception.ResourceNotFoundException("MusicPlaylist", "id", id));
        if (name != null) playlist.setName(name);
        if (comment != null) playlist.setComment(comment);
        if (isPublic != null) playlist.setIsPublic(isPublic);
        return playlistRepository.save(playlist);
    }

    @Transactional
    public void replaceSongs(MusicPlaylist playlist, List<Long> songIds) {
        entryRepository.deleteByPlaylist_Id(playlist.getId());
        if (songIds == null) return;
        final int[] position = {0};
        for (Long songId : songIds) {
            songRepository.findById(songId).ifPresent(song ->
                    entryRepository.save(MusicPlaylistEntry.builder()
                            .playlist(playlist).song(song).position(position[0]++).build()));
        }
    }

    @Transactional
    public void addSongs(MusicPlaylist playlist, List<Long> songIds) {
        List<MusicPlaylistEntry> existing = entryRepository.findByPlaylist_IdOrderByPositionAsc(playlist.getId());
        final int[] position = {existing.stream().mapToInt(MusicPlaylistEntry::getPosition).max().orElse(-1) + 1};
        if (songIds == null) return;
        for (Long songId : songIds) {
            songRepository.findById(songId).ifPresent(song ->
                    entryRepository.save(MusicPlaylistEntry.builder()
                            .playlist(playlist).song(song).position(position[0]++).build()));
        }
    }

    @Transactional
    public void removeSongs(MusicPlaylist playlist, List<Integer> indexes) {
        List<MusicPlaylistEntry> existing = entryRepository.findByPlaylist_IdOrderByPositionAsc(playlist.getId());
        List<MusicPlaylistEntry> keep = new ArrayList<>();
        for (MusicPlaylistEntry entry : existing) {
            if (indexes != null && indexes.contains(entry.getPosition())) {
                entryRepository.delete(entry);
            } else {
                keep.add(entry);
            }
        }
        // 重新压实 position
        int position = 0;
        for (MusicPlaylistEntry entry : keep) {
            entry.setPosition(position++);
            entryRepository.save(entry);
        }
    }

    @Transactional
    public void deletePlaylist(Long id) {
        playlistRepository.deleteById(id);
    }
}
