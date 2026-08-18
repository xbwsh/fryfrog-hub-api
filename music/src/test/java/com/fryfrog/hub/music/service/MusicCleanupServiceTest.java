package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicBookmarkRepository;
import com.fryfrog.hub.music.repository.MusicPlayStatRepository;
import com.fryfrog.hub.music.repository.MusicPlaylistEntryRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class MusicCleanupServiceTest {

    @Mock
    private MusicSongRepository songRepository;
    @Mock
    private MusicAlbumRepository albumRepository;
    @Mock
    private MusicArtistRepository artistRepository;
    @Mock
    private MusicPlayStatRepository playStatRepository;
    @Mock
    private MusicBookmarkRepository bookmarkRepository;
    @Mock
    private MusicPlaylistEntryRepository playlistEntryRepository;

    @InjectMocks
    private MusicCleanupService cleanupService;

    @TempDir
    Path tempDir;

    @Test
    void cleanupDeletesReferencingRowsBeforeSong() {
        MusicSong stale = new MusicSong();
        stale.setId(9L);
        stale.setTitle("stale");
        stale.setFilePath(tempDir.resolve("gone.flac").toString()); // 文件不存在

        when(songRepository.findAll()).thenReturn(List.of(stale));
        when(albumRepository.findAll()).thenReturn(List.of());
        when(artistRepository.findAll()).thenReturn(List.of());

        cleanupService.cleanupInvalidRecords();

        verify(playStatRepository).deleteBySong_Id(9L);
        verify(bookmarkRepository).deleteBySong_Id(9L);
        verify(playlistEntryRepository).deleteBySong_Id(9L);
        verify(songRepository).delete(stale);
    }
}