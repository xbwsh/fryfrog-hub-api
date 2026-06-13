# Fryfrog Hub API - Project Scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold a multi-module Maven project with Spring Boot 3.x, including parent POM, common module, music module, and app module.

**Architecture:** Multi-module Maven project where each media type (music, comic, ebook, video) is a separate module sharing a common module. The app module assembles everything as a runnable Spring Boot application.

**Tech Stack:** Java 21, Spring Boot 3.2.x, Spring Data JPA, H2, Maven

---

## Task 1: Create Parent POM

**Files:**
- Create: `pom.xml`

- [ ] **Step 1: Create parent POM with module declarations**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.fryfrog</groupId>
    <artifactId>fryfrog-hub-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>fryfrog-hub-api</name>
    <description>Unified media backend API for music, comics, ebooks, and video</description>

    <modules>
        <module>common</module>
        <module>music</module>
        <module>app</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.fryfrog</groupId>
                <artifactId>fryfrog-hub-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fryfrog</groupId>
                <artifactId>fryfrog-hub-music</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Verify POM is valid**

Run: `mvn validate -q`
Expected: No output (success)

---

## Task 2: Create Common Module

**Files:**
- Create: `common/pom.xml`
- Create: `common/src/main/java/com/fryfrog/hub/common/model/BaseEntity.java`
- Create: `common/src/main/java/com/fryfrog/hub/common/dto/ApiResponse.java`
- Create: `common/src/main/java/com/fryfrog/hub/common/exception/ResourceNotFoundException.java`
- Create: `common/src/main/java/com/fryfrog/hub/common/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create common module POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fryfrog</groupId>
        <artifactId>fryfrog-hub-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>fryfrog-hub-common</artifactId>
    <name>fryfrog-hub-common</name>
    <description>Shared entities, DTOs, and utilities</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create BaseEntity**

```java
package com.fryfrog.hub.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Create ApiResponse DTO**

```java
package com.fryfrog.hub.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
```

- [ ] **Step 4: Create ResourceNotFoundException**

```java
package com.fryfrog.hub.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, String field, Object value) {
        super(String.format("%s not found with %s: '%s'", resource, field, value));
    }
}
```

- [ ] **Step 5: Create GlobalExceptionHandler**

```java
package com.fryfrog.hub.common.exception;

import com.fryfrog.hub.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error"));
    }
}
```

- [ ] **Step 6: Compile common module**

Run: `mvn compile -pl common`
Expected: BUILD SUCCESS

---

## Task 3: Create Music Module - Entity and Repository

**Files:**
- Create: `music/pom.xml`
- Create: `music/src/main/java/com/fryfrog/hub/music/model/MusicTrack.java`
- Create: `music/src/main/java/com/fryfrog/hub/music/repository/MusicTrackRepository.java`
- Create: `music/src/main/resources/application.yml`

- [ ] **Step 1: Create music module POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fryfrog</groupId>
        <artifactId>fryfrog-hub-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>fryfrog-hub-music</artifactId>
    <name>fryfrog-hub-music</name>
    <description>Music API module - metadata extraction and streaming</description>

    <dependencies>
        <dependency>
            <groupId>com.fryfrog</groupId>
            <artifactId>fryfrog-hub-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jaudiotagger</groupId>
            <artifactId>jaudiotagger</artifactId>
            <version>2.5.3</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create MusicTrack entity**

```java
package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "music_tracks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MusicTrack extends BaseEntity {

    @Column(nullable = false)
    private String title;

    private String artist;

    private String album;

    private String albumArtist;

    private Integer trackNumber;

    private Integer discNumber;

    private Integer year;

    private String genre;

    private String filePath;

    @Column(nullable = false)
    private String fileName;

    private Long fileSize;

    private String duration;

    private String bitrate;

    private String format;

    private String coverArtPath;

    @Column(columnDefinition = "TEXT")
    private String lyrics;
}
```

- [ ] **Step 3: Create MusicTrackRepository**

```java
package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MusicTrackRepository extends JpaRepository<MusicTrack, Long> {

    List<MusicTrack> findByTitleContainingIgnoreCase(String title);

    List<MusicTrack> findByArtistContainingIgnoreCase(String artist);

    List<MusicTrack> findByAlbumContainingIgnoreCase(String album);

    List<MusicTrack> findByFilePath(String filePath);
}
```

- [ ] **Step 4: Create application.yml for music module**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:musicdb
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
  h2:
    console:
      enabled: true
      path: /h2-console

hub:
  music:
    root-path: ${MUSIC_ROOT_PATH:./music-library}
    supported-formats: mp3,flac,ogg,wav,aac,m4a
```

- [ ] **Step 5: Compile music module**

Run: `mvn compile -pl music`
Expected: BUILD SUCCESS

---

## Task 4: Create Music Module - Service

**Files:**
- Create: `music/src/main/java/com/fryfrog/hub/music/service/MusicMetadataService.java`

- [ ] **Step 1: Create MusicMetadataService**

```java
package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicMetadataService {

    private final MusicTrackRepository repository;

    @Value("${hub.music.root-path}")
    private String rootPath;

    public List<MusicTrack> getAllTracks() {
        return repository.findAll();
    }

    public MusicTrack getTrackById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MusicTrack", "id", id));
    }

    public List<MusicTrack> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title);
    }

    public List<MusicTrack> searchByArtist(String artist) {
        return repository.findByArtistContainingIgnoreCase(artist);
    }

    public MusicTrack extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateDefault();

            MusicTrack track = MusicTrack.builder()
                    .title(tag.getFirst(FieldKey.TITLE))
                    .artist(tag.getFirst(FieldKey.ARTIST))
                    .album(tag.getFirst(FieldKey.ALBUM))
                    .albumArtist(tag.getFirst(FieldKey.ALBUM_ARTIST))
                    .trackNumber(parseInteger(tag.getFirst(FieldKey.TRACK)))
                    .discNumber(parseInteger(tag.getFirst(FieldKey.DISC_NO)))
                    .year(parseInteger(tag.getFirst(FieldKey.YEAR)))
                    .genre(tag.getFirst(FieldKey.GENRE))
                    .filePath(file.getAbsolutePath())
                    .fileName(file.getName())
                    .fileSize(file.length())
                    .duration(formatDuration(audioFile.getAudioHeader().getTrackLength()))
                    .bitrate(audioFile.getAudioHeader().getBitRate() + " kbps")
                    .format(audioFile.getAudioHeader().getFormat())
                    .build();

            return repository.save(track);
        } catch (Exception e) {
            log.error("Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    public void scanDirectory(String directoryPath) {
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Not a directory: " + directoryPath);
            }

            String supportedFormats = "mp3,flac,ogg,wav,aac,m4a";
            List<String> formats = List.of(supportedFormats.split(","));

            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return formats.stream().anyMatch(name::endsWith);
                    })
                    .forEach(path -> {
                        try {
                            extractAndSaveMetadata(path.toString());
                            log.info("Indexed: {}", path.getFileName());
                        } catch (Exception e) {
                            log.warn("Failed to index: {}", path.getFileName(), e);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    public MusicTrack updateTrack(Long id, MusicTrack updatedTrack) {
        MusicTrack existing = getTrackById(id);
        existing.setTitle(updatedTrack.getTitle());
        existing.setArtist(updatedTrack.getArtist());
        existing.setAlbum(updatedTrack.getAlbum());
        existing.setAlbumArtist(updatedTrack.getAlbumArtist());
        existing.setTrackNumber(updatedTrack.getTrackNumber());
        existing.setDiscNumber(updatedTrack.getDiscNumber());
        existing.setYear(updatedTrack.getYear());
        existing.setGenre(updatedTrack.getGenre());
        return repository.save(existing);
    }

    public void deleteTrack(Long id) {
        MusicTrack track = getTrackById(id);
        repository.delete(track);
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            // Handle "1/12" format
            if (value.contains("/")) {
                value = value.split("/")[0];
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile -pl music`
Expected: BUILD SUCCESS

---

## Task 5: Create Music Module - Controller

**Files:**
- Create: `music/src/main/java/com/fryfrog/hub/music/controller/MusicController.java`

- [ ] **Step 1: Create MusicController**

```java
package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.service.MusicMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicMetadataService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getAllTracks() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllTracks()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MusicTrack>> getTrackById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getTrackById(id)));
    }

    @GetMapping("/search/title")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByTitle(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q)));
    }

    @GetMapping("/search/artist")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByArtist(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByArtist(q)));
    }

    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<String>> scanDirectory(@RequestParam String path) {
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @PostMapping("/metadata")
    public ResponseEntity<ApiResponse<MusicTrack>> extractMetadata(@RequestParam String filePath) {
        return ResponseEntity.ok(ApiResponse.success(service.extractAndSaveMetadata(filePath)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MusicTrack>> updateTrack(
            @PathVariable Long id,
            @RequestBody MusicTrack track) {
        return ResponseEntity.ok(ApiResponse.success(service.updateTrack(id, track)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrack(@PathVariable Long id) {
        service.deleteTrack(id);
        return ResponseEntity.ok(ApiResponse.success("Track deleted", null));
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `mvn compile -pl music`
Expected: BUILD SUCCESS

---

## Task 6: Create App Module

**Files:**
- Create: `app/pom.xml`
- Create: `app/src/main/java/com/fryfrog/hub/FryfrogHubApplication.java`
- Create: `app/src/main/resources/application.yml`

- [ ] **Step 1: Create app module POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fryfrog</groupId>
        <artifactId>fryfrog-hub-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>fryfrog-hub-app</artifactId>
    <name>fryfrog-hub-app</name>
    <description>Spring Boot application entry point</description>

    <dependencies>
        <dependency>
            <groupId>com.fryfrog</groupId>
            <artifactId>fryfrog-hub-music</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create main application class**

```java
package com.fryfrog.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class FryfrogHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FryfrogHubApplication.class, args);
    }
}
```

- [ ] **Step 3: Create application.yml**

```yaml
server:
  port: 8080

spring:
  application:
    name: fryfrog-hub-api

hub:
  media:
    root-path: ${MEDIA_ROOT_PATH:./media-library}

logging:
  level:
    com.fryfrog: DEBUG
    org.hibernate.SQL: WARN
```

- [ ] **Step 4: Compile entire project**

Run: `mvn compile`
Expected: BUILD SUCCESS

---

## Task 7: Verify Project Runs

- [ ] **Step 1: Build the entire project**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Start the application**

Run: `mvn spring-boot:run -pl app`
Expected: Application starts on port 8080, logs show Spring Boot startup

- [ ] **Step 3: Test health endpoint**

Run: `curl http://localhost:8080/api/v1/music`
Expected: `{"success":true,"data":[]}`

- [ ] **Step 4: Stop the application**

Press Ctrl+C or kill the process

- [ ] **Step 5: Commit initial structure**

```bash
git init
git add .
git commit -m "feat: scaffold multi-module Maven project with music module"
```
