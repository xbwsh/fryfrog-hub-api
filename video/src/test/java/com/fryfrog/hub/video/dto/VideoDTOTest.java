package com.fryfrog.hub.video.dto;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class VideoDTOTest {

    @Test
    void resolutionLabel_mapsCommonResolutions() {
        assertThat(VideoDTO.resolutionLabel("3840x2160")).isEqualTo("4K");
        assertThat(VideoDTO.resolutionLabel("1920x1080")).isEqualTo("1080p");
        assertThat(VideoDTO.resolutionLabel("1280x720")).isEqualTo("720p");
        assertThat(VideoDTO.resolutionLabel("854x480")).isEqualTo("480p");
    }

    @Test
    void resolutionLabel_normalizesPortraitOrientation() {
        // 竖屏视频按较大边判定
        assertThat(VideoDTO.resolutionLabel("1080x1920")).isEqualTo("1080p");
    }

    @Test
    void resolutionLabel_handlesNullAndInvalid() {
        assertThat(VideoDTO.resolutionLabel(null)).isNull();
        assertThat(VideoDTO.resolutionLabel("")).isNull();
        assertThat(VideoDTO.resolutionLabel("abc")).isNull();
    }
}
