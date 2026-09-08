package com.fryfrog.hub.ebook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ebook_progress",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "ebook_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "电子书阅读进度（按用户隔离）")
public class EbookProgress extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "电子书")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id", nullable = false)
    private Ebook ebook;

    @Schema(description = "阅读进度百分比（0-100）", example = "42.5")
    private Double positionPercent;

    @Schema(description = "当前章节索引", example = "7")
    private Integer chapterIndex;

    @Schema(description = "是否读完")
    @Column(nullable = false)
    private Boolean completed = false;
}
