package com.fryfrog.hub.video.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class TmdbImageProxyControllerTest {

    private final TmdbImageProxyController controller = new TmdbImageProxyController();

    @Test
    void rejectsUnsafePaths() {
        assertThat(controller.proxy(null, "w500").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.proxy("abc.jpg", "w500").getStatusCode().value()).isEqualTo(400);   // 非 / 开头
        assertThat(controller.proxy("/../../etc/passwd", "w500").getStatusCode().value()).isEqualTo(400); // 含 ..
        assertThat(controller.proxy("https://evil.com/x.jpg", "w500").getStatusCode().value()).isEqualTo(400); // 含协议
    }

    @Test
    void rejectsUnsupportedSize() {
        assertThat(controller.proxy("/abc.jpg", "w999").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.proxy("/abc.jpg", "original").getStatusCode().value()).isNotEqualTo(400);
    }

    @Test
    void returnsCachedFile_whenAlreadyDownloaded() throws Exception {
        Path cacheRoot = Files.createTempDirectory("proxy-test");
        ReflectionTestUtils.setField(controller, "cacheRoot", cacheRoot);

        @SuppressWarnings("unchecked")
        Path cached = (Path) ReflectionTestUtils.invokeMethod(controller, "resolveCacheFile", "/abc.jpg", "w500");
        assertThat(cached).isNotNull();
        Files.createDirectories(cached.getParent());
        Files.write(cached, new byte[]{1, 2, 3});

        ResponseEntity<?> resp = controller.proxy("/abc.jpg", "w500");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        Files.delete(cached);
    }
}