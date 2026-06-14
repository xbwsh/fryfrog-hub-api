package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comic_reading_progress", uniqueConstraints = @UniqueConstraint(columnNames = "comic_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画阅读进度")
public class ReadingProgress extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comic_id", nullable = false, unique = true)
    @Schema(description = "关联漫画")
    private Comic comic;

    @Schema(description = "当前页码（从1开始）", example = "42")
    private Integer currentPage;

    @Schema(description = "总页数", example = "196")
    private Integer totalPages;

    @Schema(description = "是否已读完（进度>=90%）", example = "false")
    private Boolean completed = false;
}
