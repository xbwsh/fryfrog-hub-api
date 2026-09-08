package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comic_progress",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "comic_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "漫画阅读进度（按用户隔离）")
public class ComicProgress extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "漫画")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comic_id", nullable = false)
    private Comic comic;

    @Schema(description = "当前卷/话索引", example = "3")
    @Column(name = "chapter_index")
    private Integer chapterIndex;

    @Schema(description = "当前页索引（从 0 开始）", example = "42")
    @Column(name = "page_index")
    private Integer pageIndex;

    @Schema(description = "是否读完")
    @Column(nullable = false)
    private Boolean completed = false;
}
