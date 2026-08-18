package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_bookmarks", indexes = {
    @Index(name = "idx_music_bookmark_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐书签")
public class MusicBookmark extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id")
    @Schema(description = "单曲")
    private MusicSong song;

    @Schema(description = "书签位置（秒）", example = "120.5")
    private Double positionSeconds;

    @Schema(description = "备注")
    private String comment;

    @Schema(description = "创建时间（毫秒时间戳）")
    private Long createdAtMillis;
}
