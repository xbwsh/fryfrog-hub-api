package com.fryfrog.hub.ebook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ebook_reading_progress", uniqueConstraints = @UniqueConstraint(columnNames = "ebook_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "电子书阅读进度")
public class ReadingProgress extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ebook_id", nullable = false, unique = true)
    @Schema(description = "关联电子书")
    private Ebook ebook;

    @Schema(description = "当前页码/章节数", example = "15")
    private Integer currentPage;

    @Schema(description = "总页数/章节数", example = "350")
    private Integer totalPages;

    @Schema(description = "是否已读完（进度>=90%）", example = "false")
    private Boolean completed = false;
}
