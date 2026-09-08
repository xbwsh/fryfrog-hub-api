package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookProgressRepository;
import com.fryfrog.hub.ebook.repository.EbookRepository;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EbookScanServiceTest {

    @Mock
    private EbookRepository bookRepository;
    @Mock
    private EbookProgressRepository progressRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private EbookScanService scanService;

    @TempDir
    Path libraryDir;

    @BeforeEach
    void setUp() {
        // TransactionTemplate 直接执行回调
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(bookRepository.findByLibraryId(any())).thenReturn(List.of());
    }

    private Path writeEbook(String name, String content) throws Exception {
        Path file = libraryDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void scansPlainFileWithFilenameFallback() {
        // author - title 兜底规则在 PDF 上生效
        Path pdf = libraryDir.resolve("刘慈欣 - 三体.pdf");
        try {
            Files.write(pdf, new byte[]{1, 2, 3});
        } catch (Exception ignored) {
        }

        scanService.scanAndSave(libraryDir.toString(), 1L);

        ArgumentCaptor<List<Ebook>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookRepository).saveAll(captor.capture());
        List<Ebook> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTitle()).isEqualTo("三体");
        assertThat(saved.get(0).getAuthor()).isEqualTo("刘慈欣");
        assertThat(saved.get(0).getFormat()).isEqualTo("PDF");
        assertThat(saved.get(0).getMetadataSource()).isEqualTo("scan");
    }

    @Test
    void unchangedFilesAreSkipped() throws Exception {
        Path file = writeEbook("三体.epub", "epub-content");
        scanService.scanAndSave(libraryDir.toString(), 1L);

        // 模拟首次扫描已入库
        Ebook existing = Ebook.builder()
                .filePath(file.toString())
                .fileMtime(Files.getLastModifiedTime(file).toMillis())
                .fileSize(Files.size(file))
                .title("三体").format("EPUB").build();
        when(bookRepository.findByLibraryId(1L)).thenReturn(List.of(existing));
        clearInvocations(bookRepository);

        scanService.scanAndSave(libraryDir.toString(), 1L);

        // mtime/size 未变 → 无 upsert
        verify(bookRepository, never()).saveAll(anyList());
    }

    @Test
    void missingBooksAreCleanedUp() throws Exception {
        Ebook ghost = Ebook.builder()
                .filePath(libraryDir.resolve("gone.epub").toString())
                .title("幽灵书").build();
        when(bookRepository.findByLibraryId(1L)).thenReturn(List.of(ghost));

        writeEbook("三体.epub", "epub-content");
        scanService.scanAndSave(libraryDir.toString(), 1L);

        verify(progressRepository).deleteByEbook_Id(ghost.getId());
        verify(bookRepository).delete(ghost);
    }

    @Test
    void ignoredExtensionsAreNotScanned() throws Exception {
        writeEbook("notes.txt", "text");
        writeEbook("image.jpg", "jpg");
        scanService.scanAndSave(libraryDir.toString(), 1L);

        verify(bookRepository, never()).saveAll(anyList());
        verify(bookRepository, never()).save(any(Ebook.class));
    }
}
