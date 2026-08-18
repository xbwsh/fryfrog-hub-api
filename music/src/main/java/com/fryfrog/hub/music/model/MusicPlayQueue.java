package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_play_queues", indexes = {
    @Index(name = "idx_music_play_queue_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐播放队列（每用户一条）")
public class MusicPlayQueue extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "队列中单曲 ID（逗号分隔，按顺序）")
    @Column(columnDefinition = "TEXT")
    private String entryIds;

    @Schema(description = "当前播放的单曲 ID")
    private Long currentSongId;

    @Schema(description = "当前播放位置（秒）", example = "45.5")
    private Double positionSeconds;

    @Schema(description = "播放时间（毫秒时间戳）", example = "1700000000000")
    private Long changedAtMillis;
}
