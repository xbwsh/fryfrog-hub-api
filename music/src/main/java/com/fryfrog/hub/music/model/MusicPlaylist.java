package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "music_playlists", indexes = {
    @Index(name = "idx_music_playlist_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐播放列表")
public class MusicPlaylist extends BaseEntity {

    @Schema(description = "播放列表名称", example = "开车专用")
    @Column(nullable = false)
    private String name;

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "备注")
    private String comment;

    @Schema(description = "是否对所有人可见", example = "false")
    private Boolean isPublic = false;

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @OrderBy("position ASC")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<MusicPlaylistEntry> entries = new ArrayList<>();
}
