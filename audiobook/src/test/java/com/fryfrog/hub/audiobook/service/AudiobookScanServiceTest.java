package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookChapterRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.mediacore.service.FFmpegRuntime;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudiobookScanServiceTest {

    @Mock
    private AudiobookRepository bookRepository;
    @Mock
    private AudiobookTrackRepository trackRepository;
    @Mock
    private AudiobookChapterRepository chapterRepository;
    @Mock
    private MediaProbeService probeService;
    @Mock
    private FFmpegRuntime ffmpegRuntime;
    @Mock
    private ScrapeProgressService progressService;

    @InjectMocks
    private AudiobookScanService scanService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(bookRepository.findByBookPath(anyString())).thenReturn(java.util.Optional.empty());
        when(bookRepository.save(any(Audiobook.class))).thenAnswer(inv -> {
            Audiobook b = inv.getArgument(0);
            if (b.getId() == null) b.setId(1L);
            return b;
        });
        when(trackRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(probeService.probeChapters(anyString())).thenReturn(List.of());
    }

    private void touch(Path file) throws IOException {
        Files.createFile(file);
    }

    private void probeReturns(String pathKeyword, String album, String artist, double duration) {
        when(probeService.probeAudioInfo(contains(pathKeyword))).thenReturn(Map.of(
                "duration", duration,
                "tags", Map.of("album", album, "artist", artist)));
    }

    @Test
    void multiFileDirectoryBecomesOneBookWithNaturalOrder() throws IOException {
        Path bookDir = Files.createDirectories(tempDir.resolve("刘慈欣").resolve("三体"));
        touch(bookDir.resolve("第02章.mp3"));
        touch(bookDir.resolve("第10章.mp3"));
        touch(bookDir.resolve("第01章.mp3"));
        probeReturns("三体", "三体", "刘慈欣", 1800);

        scanService.scanAndSave(tempDir.toString(), 7L);

        ArgumentCaptor<Audiobook> bookCaptor = ArgumentCaptor.forClass(Audiobook.class);
        verify(bookRepository).save(bookCaptor.capture());
        Audiobook book = bookCaptor.getValue();
        assertThat(book.getPlayType()).isEqualTo(Audiobook.TYPE_MULTI);
        assertThat(book.getTitle()).isEqualTo("三体");
        assertThat(book.getAuthor()).isEqualTo("刘慈欣");
        assertThat(book.getTrackCount()).isEqualTo(3);
        assertThat(book.getLibraryId()).isEqualTo(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AudiobookTrack>> tracksCaptor = ArgumentCaptor.forClass(List.class);
        verify(trackRepository).saveAll(tracksCaptor.capture());
        List<AudiobookTrack> tracks = tracksCaptor.getValue();
        assertThat(tracks).hasSize(3);
        assertThat(tracks).extracting(AudiobookTrack::getTrackIndex).containsExactly(0, 1, 2);
        assertThat(tracks.get(0).getFilePath()).endsWith("第01章.mp3");
        assertThat(tracks.get(1).getFilePath()).endsWith("第02章.mp3");
        assertThat(tracks.get(2).getFilePath()).endsWith("第10章.mp3");
    }

    @Test
    void singleM4bDirectoryBecomesSingleBook() throws IOException {
        Path bookDir = Files.createDirectories(tempDir.resolve("三体"));
        touch(bookDir.resolve("三体.m4b"));
        probeReturns("三体.m4b", "三体", "刘慈欣", 36000);

        scanService.scanAndSave(tempDir.toString(), 7L);

        ArgumentCaptor<Audiobook> bookCaptor = ArgumentCaptor.forClass(Audiobook.class);
        verify(bookRepository).save(bookCaptor.capture());
        Audiobook book = bookCaptor.getValue();
        assertThat(book.getPlayType()).isEqualTo(Audiobook.TYPE_SINGLE);
        assertThat(book.getTrackCount()).isEqualTo(1);
    }

    @Test
    void authorFallsBackToParentDirectoryForAuthorTitleStructure() throws IOException {
        Path bookDir = Files.createDirectories(tempDir.resolve("余华").resolve("活着"));
        touch(bookDir.resolve("01.mp3"));
        // 标签无 artist → 回退父目录名
        when(probeService.probeAudioInfo(anyString())).thenReturn(Map.of(
                "duration", 100.0, "tags", Map.of("album", "活着")));

        scanService.scanAndSave(tempDir.toString(), 7L);

        ArgumentCaptor<Audiobook> bookCaptor = ArgumentCaptor.forClass(Audiobook.class);
        verify(bookRepository).save(bookCaptor.capture());
        assertThat(bookCaptor.getValue().getAuthor()).isEqualTo("余华");
        assertThat(bookCaptor.getValue().getTitle()).isEqualTo("活着");
    }

    @Test
    void coverFileInDirectoryIsDetected() throws IOException {
        Path bookDir = Files.createDirectories(tempDir.resolve("三体"));
        touch(bookDir.resolve("01.mp3"));
        touch(bookDir.resolve("cover.jpg"));
        probeReturns("01.mp3", "三体", "刘慈欣", 100);

        scanService.scanAndSave(tempDir.toString(), 7L);

        ArgumentCaptor<Audiobook> bookCaptor = ArgumentCaptor.forClass(Audiobook.class);
        verify(bookRepository).save(bookCaptor.capture());
        assertThat(bookCaptor.getValue().getCoverArtPath()).endsWith("cover.jpg");
        // 已有封面文件，不应再调 ffmpeg 提取
        verify(ffmpegRuntime, never()).ffmpegPath();
    }

    @Test
    void missingBookDirectoryIsCleanedUp() throws IOException {
        Path bookDir = Files.createDirectories(tempDir.resolve("三体"));
        touch(bookDir.resolve("01.mp3"));
        probeReturns("01.mp3", "三体", "刘慈欣", 100);

        Audiobook ghost = Audiobook.builder()
                .title("已删除的书").bookPath("/nonexistent/path").libraryId(7L).build();
        ghost.setId(99L);
        when(bookRepository.findByLibraryId(7L)).thenReturn(List.of(ghost));

        scanService.scanAndSave(tempDir.toString(), 7L);

        verify(trackRepository).deleteByAudiobook_Id(99L);
        verify(chapterRepository).deleteByAudiobook_Id(99L);
        verify(bookRepository).delete(ghost);
    }
}
