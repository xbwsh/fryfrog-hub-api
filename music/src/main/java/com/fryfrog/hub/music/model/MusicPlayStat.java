package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "music_play_stats", indexes = {
    @Index(name = "idx_music_play_stat_song", columnList = "song_id"),
    @Index(name = "idx_music_play_stat_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐播放统计")
public class MusicPlayStat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id")
    @Schema(description = "单曲")
    private MusicSong song;

    @Schema(description = "所属用户 ID（匿名档案可为空，表示全局统计）")
    @Column(name = "user_id")
    private Long userId;

    @Schema(description = "播放次数", example = "12")
    @Column(nullable = false)
    private Integer playCount = 0;

    @Schema(description = "最近播放时间")
    private LocalDateTime lastPlayedAt;
}
