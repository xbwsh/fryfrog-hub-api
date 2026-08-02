package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.common.model.MediaLibrary;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "按资源库分组的系列列表")
public class LibrarySeriesGroupDTO {

    @Schema(description = "资源库ID")
    private Long libraryId;

    @Schema(description = "资源库名称")
    private String libraryName;

    @Schema(description = "资源库路径")
    private String libraryPath;

    @Schema(description = "资源库子类型（MOVIE/TV/MIXED）")
    private String subType;

    @Schema(description = "该资源库下的系列列表")
    private List<SeriesListDTO> series;

    @Schema(description = "该资源库下的独立视频列表")
    private List<SeriesListDTO> standaloneVideos;

    @Schema(description = "系列数量")
    private int seriesCount;

    @Schema(description = "独立视频数量")
    private int standaloneCount;

    public static LibrarySeriesGroupDTO fromLibrary(MediaLibrary library,
                                                     List<SeriesListDTO> series,
                                                     List<SeriesListDTO> standaloneVideos) {
        return LibrarySeriesGroupDTO.builder()
                .libraryId(library.getId())
                .libraryName(library.getName())
                .libraryPath(library.getPath())
                .subType(library.getSubType())
                .series(series)
                .standaloneVideos(standaloneVideos)
                .seriesCount(series.size())
                .standaloneCount(standaloneVideos.size())
                .build();
    }
}
