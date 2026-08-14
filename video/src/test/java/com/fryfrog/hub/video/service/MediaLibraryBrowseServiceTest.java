package com.fryfrog.hub.video.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MediaLibraryBrowseServiceTest {

    private final MediaLibraryBrowseService service = new MediaLibraryBrowseService();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("空路径返回根目录列表（非空）")
    void listChildren_blankPath_returnsRoots() {
        List<Map<String, Object>> result = service.listChildren(null);
        assertThat(result).isNotNull().isNotEmpty();
        assertThat(result).allSatisfy(item -> assertThat(item).containsKeys("name", "path", "writable"));
    }

    @Test
    @DisplayName("列出子目录，过滤隐藏目录并按名称排序")
    void listChildren_listsSubdirectories() throws Exception {
        Files.createDirectory(tempDir.resolve("zeta"));
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.createDirectory(tempDir.resolve(".hidden"));
        Files.writeString(tempDir.resolve("movie.mkv"), "x");

        List<Map<String, Object>> result = service.listChildren(tempDir.toString());

        assertThat(result)
                .extracting(m -> m.get("name"))
                .containsExactly("alpha", "zeta");
    }

    @Test
    @DisplayName("非目录路径返回空列表")
    void listChildren_notDirectory_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("movie.mkv");
        Files.writeString(file, "x");

        assertThat(service.listChildren(file.toString())).isEmpty();
    }
}