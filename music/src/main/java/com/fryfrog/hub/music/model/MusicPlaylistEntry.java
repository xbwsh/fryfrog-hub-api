package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_playlist_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"playlist_id", "position"}),
        indexes = @Index(name = "idx_music_playlist_entries_pl", columnList = "playlist_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "播放列表条目")
public class MusicPlaylistEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id")
    @Schema(description = "所属播放列表")
    private MusicPlaylist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id")
    @Schema(description = "单曲")
    private MusicSong song;

    @Schema(description = "在列表中的位置（0-based）", example = "0")
    @Column(nullable = false)
    private Integer position;
}
