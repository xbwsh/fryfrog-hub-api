package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.repository.MediaLibraryRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MediaLibraryService {

    private final MediaLibraryRepository repository;

    @Value("${video.root-paths:}")
    private String legacyRootPaths;

    public MediaLibraryService(MediaLibraryRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        migrateOldTypeValues();
        if (repository.count() == 0 && legacyRootPaths != null && !legacyRootPaths.isBlank()) {
            migrateLegacyConfig();
        }
    }

    private void migrateOldTypeValues() {
        for (MediaLibrary library : repository.findAll()) {
            String type = library.getType();
            if ("MOVIE".equalsIgnoreCase(type) || "TV".equalsIgnoreCase(type) || "MIXED".equalsIgnoreCase(type)) {
                library.setSubType(type.toUpperCase());
                library.setType("VIDEO");
                repository.save(library);
                log.info("Migrated library '{}' type: {} -> type=VIDEO, subType={}", library.getName(), type, library.getSubType());
            }
        }
    }

    private void migrateLegacyConfig() {
        List<String> paths = Arrays.stream(legacyRootPaths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        int order = 0;
        for (String path : paths) {
            MediaLibrary library = MediaLibrary.builder()
                    .name("默认资源库")
                    .path(path)
                    .type("VIDEO")
                    .subType("MIXED")
                    .enabled(true)
                    .sortOrder(order++)
                    .description("从 application.yml 迁移的默认配置")
                    .build();
            repository.save(library);
            log.info("Migrated legacy root path to MediaLibrary: {}", path);
        }
        if (!paths.isEmpty()) {
            log.info("Migrated {} legacy root paths to MediaLibrary entities", paths.size());
        }
    }

    public List<MediaLibrary> getAllLibraries() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    public List<MediaLibrary> getEnabledLibraries() {
        return repository.findByEnabledTrueOrderBySortOrderAsc();
    }

    public MediaLibrary getLibraryById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MediaLibrary", "id", id));
    }

    public MediaLibrary createLibrary(MediaLibrary library) {
        if (library.getSortOrder() == null) {
            library.setSortOrder((int) repository.count());
        }
        library.setPath(normalizePath(library.getPath()));
        return repository.save(library);
    }

    public MediaLibrary updateLibrary(Long id, MediaLibrary updated) {
        MediaLibrary library = getLibraryById(id);
        if (updated.getName() != null) library.setName(updated.getName());
        if (updated.getPath() != null) library.setPath(normalizePath(updated.getPath()));
        if (updated.getType() != null) library.setType(updated.getType());
        if (updated.getSubType() != null) library.setSubType(updated.getSubType());
        if (updated.getEnabled() != null) library.setEnabled(updated.getEnabled());
        if (updated.getSortOrder() != null) library.setSortOrder(updated.getSortOrder());
        if (updated.getDescription() != null) library.setDescription(updated.getDescription());
        return repository.save(library);
    }

    public void deleteLibrary(Long id) {
        MediaLibrary library = getLibraryById(id);
        int deletedOrder = library.getSortOrder() != null ? library.getSortOrder() : 0;
        repository.deleteById(id);

        // 重排后续项的 sortOrder，保持连续
        List<MediaLibrary> remaining = repository.findAllByOrderBySortOrderAsc();
        boolean needUpdate = false;
        for (MediaLibrary lib : remaining) {
            int current = lib.getSortOrder() != null ? lib.getSortOrder() : 0;
            if (current > deletedOrder) {
                lib.setSortOrder(current - 1);
                needUpdate = true;
            }
        }
        if (needUpdate) {
            repository.saveAll(remaining);
        }
    }

    public MediaLibrary toggleLibrary(Long id) {
        MediaLibrary library = getLibraryById(id);
        library.setEnabled(!library.getEnabled());
        return repository.save(library);
    }

    public List<String> getEnabledPaths() {
        return getEnabledLibraries().stream()
                .map(MediaLibrary::getPath)
                .collect(Collectors.toList());
    }

    public MediaLibrary findByPath(String path) {
        return repository.findAll().stream()
                .filter(lib -> path.startsWith(lib.getPath()) || lib.getPath().startsWith(path))
                .findFirst()
                .orElse(null);
    }

    private String normalizePath(String path) {
        if (path == null) return null;
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }
}
