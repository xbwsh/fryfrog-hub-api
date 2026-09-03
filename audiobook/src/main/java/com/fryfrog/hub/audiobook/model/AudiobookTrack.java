package com.fryfrog.hub.audiobook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "audiobook_tracks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"audiobook_id", "track_index"}),
        indexes = @Index(name = "idx_audiobook_track_file", columnList = "file_path", unique = true))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "有声书音轨（MULTI 模式一章一轨，SINGLE 模式仅一轨）")
public class AudiobookTrack extends BaseEntity {

    @Schema(description = "所属有声书")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audiobook_id", nullable = false)
    private Audiobook audiobook;

    @Schema(description = "播放顺序（0 起）", example = "0")
    @Column(name = "track_index", nullable = false)
    private Integer trackIndex;

    @Schema(description = "轨标题（章节名或文件名）", example = "第一章 疯狂年代")
    private String title;

    @Schema(description = "音频文件绝对路径")
    @Column(nullable = false)
    private String filePath;

    @Schema(description = "容器格式", example = "MP3")
    private String format;

    @Schema(description = "时长（秒）", example = "1800.0")
    private Double durationSeconds;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;
}
