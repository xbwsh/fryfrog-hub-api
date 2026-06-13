package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MusicControllerStreamingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MusicTrackRepository repository;

    private MusicTrack testTrack;
    private Path tempAudioFile;

    @BeforeEach
    void setUp() throws IOException {
        repository.deleteAll();

        tempAudioFile = Files.createTempFile("test-audio", ".mp3");
        Files.write(tempAudioFile, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05});

        testTrack = repository.save(MusicTrack.builder()
                .title("Test Song")
                .artist("Test Artist")
                .fileName("test-audio.mp3")
                .filePath(tempAudioFile.toAbsolutePath().toString())
                .fileSize(6L)
                .format("MP3")
                .build());
    }

    @Test
    void streamTrack_returnsAudioContent() throws Exception {
        mockMvc.perform(get("/api/v1/music/{id}/stream", testTrack.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().exists(HttpHeaders.CONTENT_LENGTH));
    }

    @Test
    void streamTrack_withRangeHeader_returnsPartialContent() throws Exception {
        mockMvc.perform(get("/api/v1/music/{id}/stream", testTrack.getId())
                        .header(HttpHeaders.RANGE, "bytes=0-2"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-2/6"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    if (content.length != 3) {
                        throw new AssertionError("Expected content length 3 but was " + content.length);
                    }
                });
    }

    @Test
    void streamTrack_nonExistentId_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/music/{id}/stream", 999999))
                .andExpect(status().isNotFound());
    }
}
