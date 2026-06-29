package com.fryfrog.hub.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "system_setting")
public class SystemSetting extends BaseEntity {

    @Column(name = "\"key\"", nullable = false, unique = true)
    private String key;

    @Column(name = "\"value\"", columnDefinition = "TEXT")
    private String value;

    @Column(columnDefinition = "TEXT")
    private String description;
}
