package com.fryfrog.hub.music.dto;

import com.fryfrog.hub.music.model.MusicTrack;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "专辑分组")
public class AlbumGroup {

    @Schema(description = "艺术家")
    private String artist;

    @Schema(description = "专辑名称")
    private String album;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "发行年份")
    private Integer year;

    @Schema(description = "该专辑下的所有曲目")
    private List<MusicTrack> tracks;
}
