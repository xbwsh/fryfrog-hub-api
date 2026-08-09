package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单个剧集/电影字标选项，供前端查询后选择设置。
 */
@Data
@Schema(description = "字标Logo选项（供用户选择）")
public class LogoOption {

    @Schema(description = "TMDB 图片路径（设置时回传此值）", example = "/g2Zv6Xg9PRS5ih207vUAkU1LGqU.png")
    private String filePath;

    @Schema(description = "语言", example = "zh")
    private String iso6391;

    @Schema(description = "宽度")
    private Integer width;

    @Schema(description = "高度")
    private Integer height;

    @Schema(description = "投票数")
    private Integer voteCount;

    @Schema(description = "完整图片 URL（可直接用于预览）", example = "https://image.tmdb.org/t/p/w500/g2Zv6Xg9PRS5ih207vUAkU1LGqU.png")
    private String url;

    public static LogoOption from(TmdbTvImages.Logo logo, String url) {
        LogoOption option = new LogoOption();
        option.setFilePath(logo.getFilePath());
        option.setIso6391(logo.getIso6391());
        option.setWidth(logo.getWidth());
        option.setHeight(logo.getHeight());
        option.setVoteCount(logo.getVoteCount());
        option.setUrl(url);
        return option;
    }
}
