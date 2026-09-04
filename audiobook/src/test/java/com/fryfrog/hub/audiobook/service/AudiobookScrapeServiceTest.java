package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudiobookScrapeServiceTest {

    @Mock
    private AudiobookRepository bookRepository;
    @Mock
    private AudiobookMetadataProvider providerA;
    @Mock
    private AudiobookMetadataProvider providerB;

    private AudiobookScrapeService service;

    private Audiobook book;

    @BeforeEach
    void setUp() {
        when(providerA.source()).thenReturn("itunes");
        when(providerA.displayName()).thenReturn("Apple Books");
        when(providerB.source()).thenReturn("fake");
        when(providerB.displayName()).thenReturn("Fake");
        service = new AudiobookScrapeService(bookRepository, List.of(providerA, providerB));

        book = Audiobook.builder().title("剑来").bookPath("/tmp/book")
                .libraryId(7L).playType(Audiobook.TYPE_MULTI).build();
        book.setId(1L);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(bookRepository.save(any(Audiobook.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AudiobookScrapeResult result(String id, String title, String overview) {
        AudiobookScrapeResult r = new AudiobookScrapeResult();
        r.setSource("itunes");
        r.setSourceId(id);
        r.setTitle(title);
        r.setAuthor("烽火戏诸侯");
        r.setOverview(overview);
        return r;
    }

    @Test
    void searchQueriesAllProvidersAndMerges() throws Exception {
        when(providerA.search("剑来")).thenReturn(List.of(result("111", "剑来", "简介A")));
        when(providerB.search("剑来")).thenReturn(List.of(result("222", "剑来(另一源)", null)));

        List<AudiobookScrapeResult> results = service.search("剑来", null);

        assertThat(results).hasSize(2);
        // 单个源失败不影响其他源
        when(providerB.search("剑来")).thenThrow(new RuntimeException("timeout"));
        results = service.search("剑来", null);
        assertThat(results).hasSize(1);
    }

    @Test
    void searchWithUnknownSourceThrows() {
        assertThatThrownBy(() -> service.search("剑来", "douban"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void bindFillsFieldsAndClearsOnManualEdit() throws Exception {
        when(providerA.fetch("111")).thenReturn(result("111", "剑来", "烽火戏诸侯的玄幻长篇"));

        Audiobook bound = service.bind(1L, "itunes", "111");

        assertThat(bound.getTitle()).isEqualTo("剑来");
        assertThat(bound.getOverview()).isEqualTo("烽火戏诸侯的玄幻长篇");
        assertThat(bound.getSourceId()).isEqualTo("111");
        assertThat(bound.getMetadataSource()).isEqualTo("scrape");

        Audiobook unbound = service.unbind(1L);
        assertThat(unbound.getSourceId()).isNull();
        assertThat(unbound.getMetadataSource()).isEqualTo("manual");
        assertThat(unbound.getOverview()).isEqualTo("烽火戏诸侯的玄幻长篇");
    }

    @Test
    void bindWithUnknownBookOrSourceThrows() {
        assertThatThrownBy(() -> service.bind(999L, "itunes", "111"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.bind(1L, "douban", "111"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void blankFieldsDoNotOverwriteExisting() throws Exception {
        book.setNarrator(" existing 朗读者 ".strip());
        when(providerA.fetch("111")).thenAnswer(inv -> {
            AudiobookScrapeResult r = result("111", "剑来", null);
            r.setNarrator(null);
            return r;
        });

        Audiobook bound = service.bind(1L, "itunes", "111");
        assertThat(bound.getNarrator()).isEqualTo("existing 朗读者");
    }
}
