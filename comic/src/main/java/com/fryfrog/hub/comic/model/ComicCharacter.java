package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comic_characters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画角色信息")
public class ComicCharacter extends BaseEntity {

    @Schema(description = "所属漫画 ID")
    @Column(nullable = false)
    private Long comicId;

    @Schema(description = "角色名称", example = "艾伦·耶格尔")
    @Column(nullable = false)
    private String name;

    @Schema(description = "角色原名", example = "エレン・イェーガー")
    private String originalName;

    @Schema(description = "角色图片 URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String imageUrl;

    @Schema(description = "角色图片本地路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String imagePath;

    @com.fasterxml.jackson.annotation.JsonProperty("imageUrl")
    public String getCharacterImageUrl() {
        if (getId() == null) return null;
        return "/api/v1/comic/character/" + getId() + "/image";
    }

    @Schema(description = "角色描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "角色在作品中的角色类型", example = "Main")
    private String role;

    @Schema(description = "外部数据源角色 ID", example = "12345")
    private Integer sourceCharacterId;

    @Schema(description = "数据来源", example = "anilist")
    private String source;
}
