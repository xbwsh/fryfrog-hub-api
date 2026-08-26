package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.TmdbPersonDetail;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    private TmdbTvImages.Logo logo(String filePath, String lang, int votes) {
        TmdbTvImages.Logo l = new TmdbTvImages.Logo();
        l.setFilePath(filePath);
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
                logo("/en-logo.png", "en", 10),
                logo("/zh-logo.png", "zh", 5),
                logo("/ja-logo.png", "ja", 8)));
        stubImages(images);

        String url = service.getTvLogoUrl(100L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/zh-logo.png");
    }

    @Test
    void getTvLogoUrl_fallsBackToJapaneseWhenConfiguredLanguageMissing() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/en-logo.png", "en", 3),
                logo("/ja-logo.png", "ja", 9)));
        stubImages(images);

        String url = service.getTvLogoUrl(100L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/ja-logo.png");
    }

    @Test
    void getTvLogoUrl_usesOriginalSizeForSvg() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/ja-logo.svg", "ja", 7)));
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

    @Test
    void getMovieLogoUrl_usesMovieEndpointAndReturnsUrl() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/movie-ja-logo.png", "ja", 6)));
        stubImages(images);

        String url = service.getMovieLogoUrl(200L);

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/movie-ja-logo.png");
    }

    @Test
    void getMovieLogoUrl_returnsNullWhenNoLogos() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of());
        stubImages(images);

        assertThat(service.getMovieLogoUrl(200L)).isNull();
    }

    @Test
    void getTvLogoOptions_sortedByVoteCountDesc() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of(
                logo("/low.png", "en", 2),
                logo("/high.png", "zh", 10),
                logo("/mid.png", "ja", 5)));
        stubImages(images);

        var options = service.getTvLogoOptions(100L);

        assertThat(options).hasSize(3);
        assertThat(options.get(0).getFilePath()).isEqualTo("/high.png");
        assertThat(options.get(1).getFilePath()).isEqualTo("/mid.png");
        assertThat(options.get(2).getFilePath()).isEqualTo("/low.png");
        assertThat(options.get(0).getUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/high.png");
    }

    @Test
    void getTvLogoOptions_returnsEmptyWhenNoLogos() {
        TmdbTvImages images = new TmdbTvImages();
        images.setLogos(List.of());
        stubImages(images);

        assertThat(service.getTvLogoOptions(100L)).isEmpty();
    }

    @Test
    void getLogoUrlByPath_usesOriginalForSvg() {
        assertThat(service.getLogoUrlByPath("/logo.svg"))
                .isEqualTo("https://image.tmdb.org/t/p/original/logo.svg");
        assertThat(service.getLogoUrlByPath("/logo.png"))
                .isEqualTo("https://image.tmdb.org/t/p/w500/logo.png");
        assertThat(service.getLogoUrlByPath(null)).isNull();
    }

    @Test
    void buildImageUrl_usesRequestedSize() {
        assertThat(service.buildImageUrl("/abc.jpg", "w185"))
                .isEqualTo("https://image.tmdb.org/t/p/w185/abc.jpg");
        assertThat(service.buildImageUrl(null, "w185")).isNull();
    }

    @Test
    void getPersonDetail_returnsDetailFromPrimaryLanguage() {
        TmdbPersonDetail person = new TmdbPersonDetail();
        person.setId(1136406L);
        person.setName("Tom Holland");
        person.setBiography("English actor.");
        when(restTemplate.exchange(contains("/person/1136406"), eq(HttpMethod.GET), any(HttpEntity.class), eq(TmdbPersonDetail.class)))
                .thenReturn(ResponseEntity.ok(person));

        TmdbPersonDetail result = service.getPersonDetail(1136406L);

        assertThat(result.getName()).isEqualTo("Tom Holland");
        assertThat(result.getBiography()).isEqualTo("English actor.");
        verify(restTemplate, times(1)).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
    }

    @Test
    void getPersonDetail_fallsBackWhenBiographyBlank() {
        TmdbPersonDetail zh = new TmdbPersonDetail();
        zh.setId(1136406L);
        zh.setName("Tom Holland");
        zh.setBiography("   ");
        TmdbPersonDetail ja = new TmdbPersonDetail();
        ja.setId(1136406L);
        ja.setName("トム・ホランド");
        ja.setBiography("イギリスの俳優。");
        when(restTemplate.exchange(contains("/person/1136406"), eq(HttpMethod.GET), any(HttpEntity.class), eq(TmdbPersonDetail.class)))
                .thenReturn(ResponseEntity.ok(zh), ResponseEntity.ok(ja));

        TmdbPersonDetail result = service.getPersonDetail(1136406L);

        assertThat(result.getBiography()).isEqualTo("イギリスの俳優。");
        verify(restTemplate, times(2)).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
    }

    @Test
    void getPersonDetail_cached_secondCallDoesNotHitNetwork() {
        TmdbPersonDetail person = new TmdbPersonDetail();
        person.setId(1136406L);
        person.setName("Tom Holland");
        person.setBiography("bio");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(TmdbPersonDetail.class)))
                .thenReturn(ResponseEntity.ok(person));

        service.getPersonDetail(1136406L);
        service.getPersonDetail(1136406L);

        verify(restTemplate, times(1)).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
    }
}
