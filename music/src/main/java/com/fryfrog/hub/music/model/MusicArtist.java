package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_artists", indexes = {
    @Index(name = "idx_music_artist_name", columnList = "name"),
    @Index(name = "idx_music_artist_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐歌手")
public class MusicArtist extends BaseEntity {

    @Schema(description = "歌手名", example = "周杰伦")
    @Column(nullable = false)
    private String name;

    @Schema(description = "排序名（去除 The/A 前缀等）", example = "周杰伦")
    private String sortName;

    @Schema(description = "歌手图片本地路径（artist.jpg）")
    private String coverArtPath;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;
}
