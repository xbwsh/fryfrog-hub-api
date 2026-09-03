package com.fryfrog.hub.audiobook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "audiobook_chapters",
        uniqueConstraints = @UniqueConstraint(columnNames = {"audiobook_id", "chapter_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "有声书章节（SINGLE 模式从文件内嵌章节解析；MULTI 模式章节即音轨，不入此表）")
public class AudiobookChapter extends BaseEntity {

    @Schema(description = "所属有声书")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audiobook_id", nullable = false)
    private Audiobook audiobook;

    @Schema(description = "章节顺序（0 起）", example = "0")
    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Schema(description = "章节标题", example = "第一章 疯狂年代")
    private String title;

    @Schema(description = "起始时间（秒，文件内相对时间）", example = "0.0")
    @Column(nullable = false)
    private Double startSeconds;

    @Schema(description = "结束时间（秒）", example = "1800.0")
    @Column(nullable = false)
    private Double endSeconds;
}
