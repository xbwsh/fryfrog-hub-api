package com.fryfrog.hub.ebook.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "book_sources", indexes = {
    @Index(name = "idx_book_source_enabled", columnList = "enabled"),
    @Index(name = "idx_book_source_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "书源配置")
public class BookSource extends BaseEntity {

    @Schema(description = "书源名称", example = "笔趣阁")
    @Column(nullable = false)
    private String name;

    @Schema(description = "书源作者")
    private String author;

    @Schema(description = "书源版本", example = "1.0.0")
    private String version;

    @Schema(description = "书源URL", example = "https://www.example.com")
    @Column(nullable = false, length = 500)
    private String url;

    @Schema(description = "书源规则JSON")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String ruleJson;

    @Schema(description = "净化规则JSON")
    @Column(columnDefinition = "TEXT")
    private String cleanRuleJson;

    @Schema(description = "是否启用")
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Schema(description = "书源分组", example = "小说")
    @Column(name = "\"group\"")
    private String group;

    @Schema(description = "书源类型：book/novel/comic")
    @Column(nullable = false)
    @Builder.Default
    private String sourceType = "novel";

    @Schema(description = "请求头JSON")
    @Column(columnDefinition = "TEXT")
    private String headerJson;

    @Schema(description = "排序号")
    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Schema(description = "书源说明")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "是否删除")
    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @JsonProperty("rule")
    public String getRule() {
        return ruleJson;
    }
}
