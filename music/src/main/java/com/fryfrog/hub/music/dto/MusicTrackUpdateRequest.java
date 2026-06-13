package com.fryfrog.hub.music.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MusicTrackUpdateRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String artist;

    private String album;

    private String albumArtist;

    private Integer trackNumber;

    private Integer discNumber;

    private Integer year;

    private String genre;
}
