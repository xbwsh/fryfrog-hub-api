package com.fryfrog.hub.music.dto;

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
@Schema(description = "按资源库分组的音乐内容（首页音乐模式用）")
public class MusicLibraryGroupDTO {

    @Schema(description = "资源库ID")
    private Long libraryId;

    @Schema(description = "资源库名称")
    private String libraryName;

    @Schema(description = "资源库路径")
    private String libraryPath;

    @Schema(description = "该资源库下的专辑列表")
    private List<MusicAlbumDTO> albums;

    @Schema(description = "该资源库下的歌手列表")
    private List<MusicArtistDTO> artists;

    @Schema(description = "专辑数量")
    private int albumCount;

    @Schema(description = "歌手数量")
    private int artistCount;
}
