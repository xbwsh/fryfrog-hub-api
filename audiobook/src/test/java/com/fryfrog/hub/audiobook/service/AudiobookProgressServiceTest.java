package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookProgressRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class AudiobookProgressServiceTest {

    @Mock
    private AudiobookProgressRepository progressRepository;
    @Mock
    private AudiobookRepository bookRepository;
    @Mock
    private AudiobookTrackRepository trackRepository;

    @InjectMocks
    private AudiobookProgressService service;

    private Audiobook book;

    @BeforeEach
    void setUp() {
        book = Audiobook.builder().title("三体").playType(Audiobook.TYPE_MULTI).build();
        book.setId(1L);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(progressRepository.save(any(AudiobookProgress.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AudiobookTrack track(int index, double duration) {
        return AudiobookTrack.builder().trackIndex(index).durationSeconds(duration)
                .audiobook(book).filePath("f" + index + ".mp3").build();
    }

    @Test
    void updatePositionCreatesProgress() {
        when(progressRepository.findByUserIdAndAudiobook_Id(10L, 1L)).thenReturn(Optional.empty());
        when(trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(1L))
                .thenReturn(List.of(track(0, 1800), track(1, 1800)));

        AudiobookProgress progress = service.updatePosition(10L, 1L, 1, 600d);

        assertThat(progress.getUserId()).isEqualTo(10L);
        assertThat(progress.getTrackIndex()).isEqualTo(1);
        assertThat(progress.getPositionSeconds()).isEqualTo(600d);
        assertThat(progress.getCompleted()).isFalse();
    }

    @Test
    void lastTrackOverThresholdAutoCompletes() {
        when(progressRepository.findByUserIdAndAudiobook_Id(10L, 1L)).thenReturn(Optional.empty());
        when(trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(1L))
                .thenReturn(List.of(track(0, 1800), track(1, 1800)));

        AudiobookProgress progress = service.updatePosition(10L, 1L, 1, 1750d);

        assertThat(progress.getCompleted()).isTrue();
    }

    @Test
    void nonLastTrackDoesNotAutoComplete() {
        when(progressRepository.findByUserIdAndAudiobook_Id(10L, 1L)).thenReturn(Optional.empty());
        when(trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(1L))
                .thenReturn(List.of(track(0, 1800), track(1, 1800)));

        AudiobookProgress progress = service.updatePosition(10L, 1L, 0, 1790d);

        assertThat(progress.getCompleted()).isFalse();
    }

    @Test
    void unknownBookThrows() {
        when(bookRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updatePosition(10L, 999L, 0, 0d))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setCompletedPushesPositionToLastTrackEnd() {
        when(progressRepository.findByUserIdAndAudiobook_Id(10L, 1L)).thenReturn(Optional.empty());
        AudiobookTrack last = track(2, 1800);
        when(trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(1L))
                .thenReturn(List.of(track(0, 1800), track(1, 1800), last));

        AudiobookProgress progress = service.setCompleted(10L, 1L, true);

        assertThat(progress.getCompleted()).isTrue();
        assertThat(progress.getTrackIndex()).isEqualTo(2);
        assertThat(progress.getPositionSeconds()).isEqualTo(1800d);
    }
}
