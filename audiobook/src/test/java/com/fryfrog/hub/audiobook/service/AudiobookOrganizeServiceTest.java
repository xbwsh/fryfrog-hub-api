package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudiobookOrganizeServiceTest {

    @Mock
    private AudiobookRepository bookRepository;
    @Mock
    private AudiobookTrackRepository trackRepository;
    @Mock
    private MediaLibraryService mediaLibraryService;

    private AudiobookOrganizeService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 直通事务模板：单测直接执行回调
        TransactionTemplate passthrough = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new AudiobookOrganizeService(bookRepository, trackRepository, mediaLibraryService, passthrough);

        MediaLibrary lib = new MediaLibrary();
        lib.setId(7L);
        lib.setPath(tempDir.toString());
        lib.setType("AUDIOBOOK");
        lenient().when(mediaLibraryService.getLibraryById(7L)).thenReturn(lib);

        lenient().when(bookRepository.findByLibraryId(7L)).thenReturn(List.of());
        lenient().when(bookRepository.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(trackRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(trackStore.get(inv.getArgument(0, Long.class))));
        lenient().when(bookRepository.save(any(Audiobook.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(trackRepository.save(any(AudiobookTrack.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private final java.util.concurrent.ConcurrentMap<Long, AudiobookTrack> trackStore =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Audiobook flatBook(String title, String... fileNames) throws IOException {
        Audiobook book = Audiobook.builder()
                .title(title).bookPath(tempDir.toString())
                .libraryId(7L).playType(Audiobook.TYPE_MULTI).build();
        book.setId(1L);
        List<AudiobookTrack> tracks = new java.util.ArrayList<>();
        int index = 0;
        for (String name : fileNames) {
            Path file = tempDir.resolve(name);
            if (!Files.exists(file)) Files.createFile(file);
            AudiobookTrack track = AudiobookTrack.builder()
                    .trackIndex(index).title(name).filePath(file.toString())
                    .audiobook(book).build();
            track.setId((long) (index + 1));
            trackStore.put(track.getId(), track);
            tracks.add(track);
            index++;
        }
        when(trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(1L)).thenReturn(tracks);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(bookRepository.findByLibraryId(7L)).thenReturn(List.of(book));
        return book;
    }
    @Test
    void dryRunPlansWithoutMovingFiles() throws IOException {
        flatBook("剑来001", "剑来001.mp3", "剑来002.mp3", "剑来003.mp3");

        Map<String, Object> result = service.organize(7L, true);

        assertThat(result.get("dryRun")).isEqualTo(true);
        assertThat(result.get("movedOrPlanned")).isEqualTo(3);
        // dryRun 不动文件
        assertThat(Files.exists(tempDir.resolve("剑来001.mp3"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("剑来").resolve("剑来第一季").resolve("001.mp3"))).isFalse();
    }

    @Test
    void organizeMovesFilesAndUpdatesDb() throws IOException {
        flatBook("剑来001", "剑来001.mp3", "剑来002.mp3");

        service.organize(7L, false);

        Path seasonDir = tempDir.resolve("剑来").resolve("剑来第一季");
        assertThat(Files.exists(seasonDir.resolve("001.mp3"))).isTrue();
        assertThat(Files.exists(seasonDir.resolve("002.mp3"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("剑来001.mp3"))).isFalse();
        // DB 记录指向新路径
        assertThat(trackStore.get(1L).getFilePath()).isEqualTo(seasonDir.resolve("001.mp3").toString());
    }

    @Test
    void singleDigitWithSeparatorIsParsed() {
        assertThat(AudiobookOrganizeService.stripEpisodeOf("剑来 #1")).isEqualTo("剑来");
        assertThat(AudiobookOrganizeService.stripEpisodeOf("剑来 1")).isEqualTo("剑来");
        assertThat(AudiobookOrganizeService.stripEpisodeOf("剑来001")).isEqualTo("剑来");
        assertThat(AudiobookOrganizeService.stripEpisodeOf("剑来-02")).isEqualTo("剑来");
    }

    @Test
    void embeddedDigitsInTitleAreNotStripped() {
        // 无分隔符的单数字不拆（防误伤书名）
        assertThat(AudiobookOrganizeService.stripEpisodeOf("三体2")).isNull();
        assertThat(AudiobookOrganizeService.stripEpisodeOf("活着")).isNull();
    }

    @Test
    void seasonPartParsing() {
        assertThat(AudiobookOrganizeService.seasonPartOf("剑来第一季")).isEqualTo(1);
        assertThat(AudiobookOrganizeService.seasonPartOf("剑来第二季")).isEqualTo(2);
        assertThat(AudiobookOrganizeService.seasonPartOf("第2部")).isEqualTo(2);
        assertThat(AudiobookOrganizeService.seasonPartOf("卷三")).isEqualTo(3);
        assertThat(AudiobookOrganizeService.seasonPartOf("Part 4")).isEqualTo(4);
        assertThat(AudiobookOrganizeService.seasonPartOf("活着")).isNull();
    }
}
