package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.TmdbTvImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class TmdbServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private TmdbService service;

    @BeforeEach
    void setUp() {
        service = new TmdbService(restTemplate);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "language", "zh-CN");
        ReflectionTestUtils.setField(service, "imageSize", "w500");
    }

    private TmdbTvImages.Logo logo(String filePath, String fileType, String lang, int votes) {
        TmdbTvImages.Logo l = new TmdbTvImages.Logo();
        l.setFilePath(filePath);
        l.setFileType(fileType);
        l.setIso6391(lang);
        l.setVoteCount(votes);
        return l;
    }

    private void stubImages(TmdbTvImages images) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(TmdbTvImages.class)))
                .thenReturn(ResponseEntity.ok(images));
    }

    @Test
    void getTvLogoUrl_prefersConfiguredLanguage() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/en-logo.png", ".png", "en", 10),
                logo("/zh-logo.png", ".png", "zh", 5),
                logo("/ja-logo.png", ".png", "ja", 8)));
        stubImages(images);

        String url = service.getTvLogoUrl(100L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/zh-logo.png");
    }

    @Test
    void getTvLogoUrl_fallsBackToJapaneseWhenConfiguredLanguageMissing() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/en-logo.png", ".png", "en", 3),
                logo("/ja-logo.png", ".png", "ja", 9)));
        stubImages(images);

        String url = service.getTvLogoUrl(100L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/ja-logo.png");
    }

    @Test
    void getTvLogoUrl_usesOriginalSizeForSvg() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/ja-logo.svg", ".svg", "ja", 7)));
        stubImages(images);

        String url = service.getTvLogoUrl(100L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/original/ja-logo.svg");
    }

    @Test
    void getTvLogoUrl_returnsNullWhenNoLogos() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of());
        stubImages(images);

        assertThat(service.getTvLogoUrl(100L)).isNull();
    }
}
