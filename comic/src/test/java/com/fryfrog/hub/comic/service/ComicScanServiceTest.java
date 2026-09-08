package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.comic.repository.ComicChapterRepository;
import com.fryfrog.hub.comic.repository.ComicProgressRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComicScanServiceTest {

    @Mock
    private ComicRepository comicRepository;
    @Mock
    private ComicChapterRepository chapterRepository;
    @Mock
    private ComicProgressRepository progressRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private final ComicPageService pageService = new ComicPageService();

    private ComicScanService scanService;

    @TempDir
    Path libraryDir;

    @BeforeEach
    void setUp() {
        scanService = new ComicScanService(comicRepository, chapterRepository,
                progressRepository, pageService, transactionTemplate);
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(comicRepository.findByLibraryId(any())).thenReturn(List.of());
    }

    private byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10};
    }

    private void writeCbz(Path path, String... pageNames) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(path.toFile()))) {
            for (String page : pageNames) {
                zip.putNextEntry(new ZipEntry(page));
                zip.write(jpeg());
                zip.closeEntry();
            }
        }
    }

    @Test
    void directorySeriesWithChapterSubdirs() throws Exception {
        Path series = libraryDir.resolve("进击的巨人");
        Files.createDirectories(series.resolve("第01卷"));
        Files.createDirectories(series.resolve("第10卷")); // 自然序验证
        Files.write(series.resolve("第01卷").resolve("002.jpg"), jpeg());
        Files.write(series.resolve("第01卷").resolve("001.jpg"), jpeg());
        Files.write(series.resolve("第10卷").resolve("a.png"), jpeg());
        Files.write(series.resolve("cover.jpg"), jpeg()); // 非页图目录的散图不建卷

        scanService.scanAndSave(libraryDir.toString(), 1L);

        ArgumentCaptor<List<Comic>> comics = ArgumentCaptor.forClass(List.class);
        verify(comicRepository).saveAll(comics.capture());
        assertThat(comics.getValue()).hasSize(1);
        Comic comic = comics.getValue().get(0);
        assertThat(comic.getTitle()).isEqualTo("进击的巨人");
        assertThat(comic.getTotalChapters()).isEqualTo(2);

        ArgumentCaptor<List<ComicChapter>> chapters = ArgumentCaptor.forClass(List.class);
        verify(chapterRepository).saveAll(chapters.capture());
        List<ComicChapter> saved = chapters.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getTitle()).isEqualTo("第01卷");
        assertThat(saved.get(0).getType()).isEqualTo("DIRECTORY");
        assertThat(saved.get(0).getPageCount()).isEqualTo(2);
        assertThat(saved.get(1).getTitle()).isEqualTo("第10卷");
        assertThat(saved.get(1).getChapterIndex()).isEqualTo(1);
    }

    @Test
    void rootArchivesGroupIntoSeriesByVolumeSuffix() throws Exception {
        writeCbz(libraryDir.resolve("一拳超人 第02卷.cbz"), "b.jpg");
        writeCbz(libraryDir.resolve("一拳超人 第01卷.cbz"), "a.jpg");

        scanService.scanAndSave(libraryDir.toString(), 1L);

        ArgumentCaptor<List<Comic>> comics = ArgumentCaptor.forClass(List.class);
        verify(comicRepository).saveAll(comics.capture());
        assertThat(comics.getValue()).hasSize(1);
        Comic comic = comics.getValue().get(0);
        assertThat(comic.getTitle()).isEqualTo("一拳超人");

        ArgumentCaptor<List<ComicChapter>> chapters = ArgumentCaptor.forClass(List.class);
        verify(chapterRepository).saveAll(chapters.capture());
        assertThat(chapters.getValue()).hasSize(2);
        // 第01卷在前
        assertThat(chapters.getValue().get(0).getTitle()).isEqualTo("一拳超人 第01卷");
        assertThat(chapters.getValue().get(0).getType()).isEqualTo("ARCHIVE");
        assertThat(chapters.getValue().get(0).getPageCount()).isEqualTo(1);
    }

    @Test
    void dirWithLooseImagesBecomesSingleChapter() throws Exception {
        Path series = libraryDir.resolve("散图作品");
        Files.createDirectories(series);
        Files.write(series.resolve("p1.jpg"), jpeg());
        Files.write(series.resolve("p2.jpg"), jpeg());

        scanService.scanAndSave(libraryDir.toString(), 1L);

        ArgumentCaptor<List<ComicChapter>> chapters = ArgumentCaptor.forClass(List.class);
        verify(chapterRepository).saveAll(chapters.capture());
        assertThat(chapters.getValue()).hasSize(1);
        assertThat(chapters.getValue().get(0).getPageCount()).isEqualTo(2);
    }

    @Test
    void unsupportedArchivesAreSkipped() throws Exception {
        Files.write(libraryDir.resolve("打不开.cbr"), jpeg());
        scanService.scanAndSave(libraryDir.toString(), 1L);

        verify(comicRepository).saveAll(anyList());
        ArgumentCaptor<List<Comic>> comics = ArgumentCaptor.forClass(List.class);
        verify(comicRepository).saveAll(comics.capture());
        assertThat(comics.getValue()).isEmpty();
    }

    @Test
    void missingComicsAreCleanedUp() throws Exception {
        Path ghost = libraryDir.resolve("ghost");
        Comic existing = Comic.builder()
                .bookPath(ghost.toString())
                .title("幽灵漫画").build();
        existing.setId(9L);
        when(comicRepository.findByLibraryId(1L)).thenReturn(List.of(existing));
        when(chapterRepository.findByComic_IdOrderByChapterIndexAsc(9L)).thenReturn(List.of());

        // 建一个正常作品避免全空
        Path series = libraryDir.resolve("正常作品");
        Files.createDirectories(series.resolve("第01卷"));
        Files.write(series.resolve("第01卷").resolve("1.jpg"), jpeg());

        scanService.scanAndSave(libraryDir.toString(), 1L);

        verify(progressRepository).deleteByComic_Id(9L);
        verify(chapterRepository).deleteByComic_Id(9L);
        verify(comicRepository).delete(existing);
    }

    @Test
    void seriesTitleOfStripsVolumeSuffix() {
        assertThat(ComicScanService.seriesTitleOf("一拳超人 第01卷")).isEqualTo("一拳超人");
        assertThat(ComicScanService.seriesTitleOf("one punch Vol.2")).isEqualTo("one punch");
        assertThat(ComicScanService.seriesTitleOf("海贼王 v03")).isEqualTo("海贼王");
        assertThat(ComicScanService.seriesTitleOf("第01卷")).isEqualTo("第01卷"); // 剥完为空不剥
        assertThat(ComicScanService.seriesTitleOf("普通作品")).isEqualTo("普通作品");
    }
}
