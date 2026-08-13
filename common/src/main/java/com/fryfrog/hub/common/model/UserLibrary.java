package com.fryfrog.hub.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_libraries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "library_id"}),
        indexes = @Index(name = "idx_user_libraries_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "用户可访问媒体库授权")
public class UserLibrary extends BaseEntity {

    @Schema(description = "用户 ID")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "媒体库 ID")
    @Column(name = "library_id", nullable = false)
    private Long libraryId;
}