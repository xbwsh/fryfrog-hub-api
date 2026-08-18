package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicBookmarkRepository;
import com.fryfrog.hub.music.repository.MusicPlayStatRepository;
import com.fryfrog.hub.music.repository.MusicPlaylistEntryRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 音乐库清理：删除文件已不存在的单曲（先清引用它的播放统计/书签/播放列表条目，
 * 避免外键约束），并回收空歌手/空专辑。
 * <p>独立成 Bean 以保证 {@link Transactional} 通过 Spring 代理生效
 * （在 MusicScanService 内部自调用会被忽略）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MusicCleanupService {

    private final MusicSongRepository songRepository;
    private final MusicAlbumRepository albumRepository;
    private final MusicArtistRepository artistRepository;
    private final MusicPlayStatRepository playStatRepository;
    private final MusicBookmarkRepository bookmarkRepository;
    private final MusicPlaylistEntryRepository playlistEntryRepository;

    @Transactional
    public void cleanupInvalidRecords() {
        int removed = 0;
        for (MusicSong song : songRepository.findAll()) {
            if (song.getFilePath() == null || !Files.exists(Paths.get(song.getFilePath()))) {
                // 先清理引用该单曲的播放统计/书签/播放列表条目，避免外键约束
                playStatRepository.deleteBySong_Id(song.getId());
                bookmarkRepository.deleteBySong_Id(song.getId());
                playlistEntryRepository.deleteBySong_Id(song.getId());
                songRepository.delete(song);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[MusicScan] Removed {} stale songs", removed);
        }
        albumRepository.findAll().stream()
                .filter(a -> songRepository.findByAlbum_IdOrderByDiscNumberAscTrackNumberAsc(a.getId()).isEmpty())
                .forEach(albumRepository::delete);
        artistRepository.findAll().stream()
                .filter(a -> albumRepository.findByArtist_IdOrderByYearAsc(a.getId()).isEmpty())
                .filter(a -> songRepository.findByArtist_IdOrderByAlbumNameAsc(a.getId()).isEmpty())
                .forEach(artistRepository::delete);
    }
}
