package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "音乐专辑")
public class MusicAlbumDTO {

    private Long id;
    private String title;
    private String artistName;
    private Long artistId;
    private Integer year;
    private String genre;
    private String coverUrl;
    private Integer trackCount;
    private int durationSeconds;
    private boolean starred;
    private Integer rating;
    private List<MusicSongDTO> songs;
}