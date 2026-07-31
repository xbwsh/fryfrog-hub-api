package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.BookSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "书源DTO")
public class BookSourceDTO {

    @Schema(description = "书源ID")
    private Long id;

    @Schema(description = "书源名称")
    private String name;

    @Schema(description = "书源作者")
    private String author;

    @Schema(description = "书源版本")
    private String version;

    @Schema(description = "书源URL")
    private String url;

    @Schema(description = "书源规则JSON")
    private String ruleJson;

    @Schema(description = "净化规则JSON")
    private String cleanRuleJson;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "书源分组")
    private String group;

    @Schema(description = "书源类型")
    private String sourceType;

    @Schema(description = "请求头JSON")
    private String headerJson;

    @Schema(description = "排序号")
    private Integer sortOrder;

    @Schema(description = "书源说明")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static BookSourceDTO fromEntity(BookSource entity) {
        if (entity == null) {
            return null;
        }
        return BookSourceDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .author(entity.getAuthor())
                .version(entity.getVersion())
                .url(entity.getUrl())
                .ruleJson(entity.getRuleJson())
                .cleanRuleJson(entity.getCleanRuleJson())
                .enabled(entity.getEnabled())
                .group(entity.getGroup())
                .sourceType(entity.getSourceType())
                .headerJson(entity.getHeaderJson())
                .sortOrder(entity.getSortOrder())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public BookSource toEntity() {
        BookSource entity = BookSource.builder()
                .name(this.name)
                .author(this.author)
                .version(this.version)
                .url(this.url)
                .ruleJson(this.ruleJson)
                .cleanRuleJson(this.cleanRuleJson)
                .enabled(this.enabled)
                .group(this.group)
                .sourceType(this.sourceType)
                .headerJson(this.headerJson)
                .sortOrder(this.sortOrder)
                .description(this.description)
                .deleted(false)
                .build();
        entity.setId(this.id);
        return entity;
    }
}
