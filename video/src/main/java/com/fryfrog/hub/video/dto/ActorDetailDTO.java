package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "演员详情（个人信息 + 作品列表）")
public class ActorDetailDTO {

    @Schema(description = "本地演员ID")
    private Long id;

    @Schema(description = "演员姓名")
    private String name;

    @Schema(description = "演员头像URL（签名）")
    private String imageUrl;

    @Schema(description = "TMDB 人物ID")
    private Long tmdbId;

    @Schema(description = "个人简介")
    private String biography;

    @Schema(description = "艺名/别名")
    private List<String> alsoKnownAs;

    @Schema(description = "生日", example = "1996-06-01")
    private String birthday;

    @Schema(description = "逝世日期（在世为 null）")
    private String deathday;

    @Schema(description = "性别代码（1=女 2=男 3=非二元 0=未设置）")
    private Integer gender;

    @Schema(description = "性别中文标签")
    private String genderLabel;

    @Schema(description = "出生地")
    private String placeOfBirth;

    @Schema(description = "个人主页")
    private String homepage;

    @Schema(description = "IMDB ID", example = "nm4043618")
    private String imdbId;

    @Schema(description = "从业方向", example = "Acting")
    private String knownForDepartment;

    @Schema(description = "人气值")
    private Double popularity;

    @Schema(description = "出演作品数")
    private int castCount;

    @Schema(description = "幕后参与作品数")
    private int crewCount;

    @Schema(description = "参与作品总数")
    private int totalCredits;

    @Schema(description = "知名作品（按票数取前 10，去重）")
    private List<Credit> knownFor;

    @Schema(description = "全部作品（cast=出演，crew=幕后）")
    private CreditList credits;

    @Data
    @Schema(description = "作品条目")
    public static class Credit {
        @Schema(description = "作品 TMDB ID")
        private Long id;

        @Schema(description = "媒体类型 movie/tv")
        private String mediaType;

        @Schema(description = "作品标题")
        private String title;

        @Schema(description = "原始标题")
        private String originalTitle;

        @Schema(description = "出演角色")
        private String character;

        @Schema(description = "幕后职务")
        private String job;

        @Schema(description = "幕后部门")
        private String department;

        @Schema(description = "上映/首播日期")
        private String releaseDate;

        @Schema(description = "年份")
        private Integer year;

        @Schema(description = "海报URL")
        private String posterUrl;

        @Schema(description = "简介")
        private String overview;

        @Schema(description = "评分")
        private Double voteAverage;

        @Schema(description = "评分人数")
        private Integer voteCount;

        @Schema(description = "总集数（剧集）")
        private Integer episodeCount;

        @Schema(description = "是否为成人内容")
        private Boolean adult;
    }

    @Data
    @Schema(description = "作品列表（按出演/幕后分组）")
    public static class CreditList {
        private List<Credit> cast = new java.util.ArrayList<>();
        private List<Credit> crew = new java.util.ArrayList<>();
    }
}