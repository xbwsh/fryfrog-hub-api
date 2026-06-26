package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "video_actors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "视频演员信息")
public class VideoActor extends BaseEntity {

    @Schema(description = "所属视频 ID")
    @Column(nullable = false)
    private Long videoId;

    @Schema(description = "演员姓名", example = "吴京")
    @Column(nullable = false)
    private String name;

    @Schema(description = "角色名称", example = "刘培强")
    private String character;

    @Schema(description = "演员头像本地路径")
    private String imagePath;

    @Schema(description = "演员头像远程URL")
    private String imageUrl;

    @Schema(description = "TMDB演员ID", example = "12345")
    private Long sourceActorId;
}
