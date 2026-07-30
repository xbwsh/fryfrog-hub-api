package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "季度信息，包含该季的所有剧集")
public class SeasonDTO {

    @Schema(description = "季数")
    private Integer seasonNumber;

    @Schema(description = "该季的剧集列表")
    private List<VideoDTO> episodes;

    public static SeasonDTO of(Integer seasonNumber, List<VideoDTO> episodes) {
        SeasonDTO dto = new SeasonDTO();
        dto.setSeasonNumber(seasonNumber);
        dto.setEpisodes(episodes);
        return dto;
    }
}
