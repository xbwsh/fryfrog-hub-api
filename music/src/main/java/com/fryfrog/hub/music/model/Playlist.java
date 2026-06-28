package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "playlists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "播放列表")
public class Playlist extends BaseEntity {

    @Schema(description = "播放列表名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "播放列表描述")
    private String description;

    @Schema(description = "封面图片URL")
    private String coverUrl;

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PlaylistTrack> tracks = new ArrayList<>();
}
