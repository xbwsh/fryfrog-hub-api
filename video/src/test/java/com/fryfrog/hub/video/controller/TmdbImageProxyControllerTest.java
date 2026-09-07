package com.fryfrog.hub.video.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

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
    }
}
