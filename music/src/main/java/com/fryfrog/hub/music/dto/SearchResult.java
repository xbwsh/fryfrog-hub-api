package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "音乐搜索结果（聚合API统一格式）")
public class SearchResult {

    @Schema(description = "歌曲ID")
    private String id;

    @Schema(description = "歌曲标题")
    private String name;

    @Schema(description = "艺术家")
    private String artist;

    @Schema(description = "专辑")
    private String album;

    @Schema(description = "数据来源平台", example = "netease")
    private String platform;

    @Schema(description = "歌词URL（LRC格式）")
    private String lrcUrl;

    @Schema(description = "封面图片URL")
    private String picUrl;

    public static SearchResult fromDict(java.util.Map<String, Object> data) {
        SearchResult r = new SearchResult();
        r.id = getString(data, "id");
        r.name = getString(data, "name");
        r.artist = getString(data, "artist");
        r.album = getString(data, "album");
        r.platform = getString(data, "platform");
        r.lrcUrl = getString(data, "lrc");
        r.picUrl = getString(data, "pic");
        return r;
    }

    private static String getString(java.util.Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : "";
    }
}
