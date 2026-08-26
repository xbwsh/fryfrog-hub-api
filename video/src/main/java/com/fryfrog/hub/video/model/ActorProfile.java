package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "actor_profiles", indexes = {
    @Index(name = "idx_actor_profile_actor_id", columnList = "actor_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "演员详情缓存（TMDB 人物资料落库）")
public class ActorProfile extends BaseEntity {

    @Schema(description = "本地演员ID（video_actors.id）")
    @Column(name = "actor_id", nullable = false, unique = true)
    private Long actorId;

    @Schema(description = "TMDB人物ID")
    @Column(name = "tmdb_id")
    private Long tmdbId;

    @Schema(description = "演员姓名")
    private String name;

    @Schema(description = "个人简介")
    @Column(columnDefinition = "TEXT")
    private String biography;

    @Schema(description = "艺名/别名（JSON 数组）")
    @Column(columnDefinition = "TEXT")
    private String alsoKnownAsJson;

    @Schema(description = "生日", example = "1996-06-01")
    private String birthday;

    @Schema(description = "逝世日期")
    private String deathday;

    @Schema(description = "性别代码（1=女 2=男 3=非二元 0=未设置）")
    private Integer gender;

    @Schema(description = "出生地")
    private String placeOfBirth;

    @Schema(description = "个人主页")
    private String homepage;

    @Schema(description = "IMDB ID")
    private String imdbId;

    @Schema(description = "从业方向")
    private String knownForDepartment;

    @Schema(description = "人气值")
    private Double popularity;

    @Schema(description = "出演作品（JSON 数组）")
    @Column(columnDefinition = "TEXT")
    private String castJson;

    @Schema(description = "幕后作品（JSON 数组）")
    @Column(columnDefinition = "TEXT")
    private String crewJson;

    @Schema(description = "数据获取时间")
    private LocalDateTime fetchedAt;
}