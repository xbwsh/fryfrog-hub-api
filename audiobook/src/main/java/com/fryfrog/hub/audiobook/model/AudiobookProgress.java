package com.fryfrog.hub.audiobook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "audiobook_progress",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "audiobook_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "有声书收听进度（按用户隔离）")
public class AudiobookProgress extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "有声书")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audiobook_id", nullable = false)
    private Audiobook audiobook;

    @Schema(description = "当前音轨索引", example = "3")
    @Column(name = "track_index")
    private Integer trackIndex;

    @Schema(description = "当前轨内位置（秒）", example = "620.5")
    private Double positionSeconds;

    @Schema(description = "是否听完")
    @Column(nullable = false)
    private Boolean completed = false;
}
