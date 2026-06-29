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

    @Value("${hub.video.root-paths:./media-library/video}")
    private String legacyRootPaths;

    public MediaLibraryService(MediaLibraryRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        if (repository.count() == 0 && legacyRootPaths != null && !legacyRootPaths.isBlank()) {
            migrateLegacyConfig();
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
                    .type("MIXED")
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
        return repository.save(library);
    }

    public MediaLibrary updateLibrary(Long id, MediaLibrary updated) {
        MediaLibrary library = getLibraryById(id);
        library.setName(updated.getName());
        library.setPath(updated.getPath());
        library.setType(updated.getType());
        library.setEnabled(updated.getEnabled());
        library.setSortOrder(updated.getSortOrder());
        library.setDescription(updated.getDescription());
        return repository.save(library);
    }

    public void deleteLibrary(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", id);
        }
        repository.deleteById(id);
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
}
