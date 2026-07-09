package com.fryfrog.hub.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "media_series_characters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "系列角色信息")
public class MediaSeriesCharacter extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    @JsonIgnore
    private MediaSeries series;

    @Schema(description = "角色名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "角色原名")
    private String originalName;

    @Schema(description = "角色图片 URL")
    @JsonIgnore
    private String imageUrl;

    @Schema(description = "角色图片本地路径")
    @JsonIgnore
    private String imagePath;

    @Schema(description = "角色描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "角色类型", example = "Main")
    private String role;

    @Schema(description = "外部数据源角色 ID")
    private Integer sourceCharacterId;

    @Schema(description = "数据来源", example = "bangumi")
    private String source;

    @JsonProperty("imageUrl")
    public String getCharacterImageUrl() {
        if (getId() == null) return null;
        return "/api/v1/media/series/character/" + getId() + "/image";
    }
}
