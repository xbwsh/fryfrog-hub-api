package com.fryfrog.hub.music.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "playlist_tracks", indexes = {
    @Index(name = "idx_playlist_track_playlist", columnList = "playlist_id"),
    @Index(name = "idx_playlist_track_track", columnList = "track_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "播放列表曲目关联")
public class PlaylistTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    @JsonIgnore
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "track_id", nullable = false)
    private MusicTrack track;

    @Schema(description = "添加时间")
    private LocalDateTime addedAt = LocalDateTime.now();

    @Schema(description = "播放列表中的位置")
    private Integer position;
}
