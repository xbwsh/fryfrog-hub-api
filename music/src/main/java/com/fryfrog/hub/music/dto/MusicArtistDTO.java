package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "音乐歌手")
public class MusicArtistDTO {

    private Long id;
    private String name;
    private String sortName;
    private String coverUrl;
    private int albumCount;
    private boolean starred;
    private List<MusicAlbumDTO> albums;
}