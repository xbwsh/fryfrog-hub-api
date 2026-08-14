package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.model.UserLibrary;
import com.fryfrog.hub.common.repository.MediaLibraryRepository;
import com.fryfrog.hub.common.repository.UserLibraryRepository;
import com.fryfrog.hub.common.security.UserContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MediaLibraryService {

    private final MediaLibraryRepository repository;
    private final UserLibraryRepository userLibraryRepository;
    private final UserService userService;

    @Value("${video.root-paths:}")
    private String legacyRootPaths;

    public MediaLibraryService(MediaLibraryRepository repository,
                               UserLibraryRepository userLibraryRepository,
                               UserService userService) {
        this.repository = repository;
        this.userLibraryRepository = userLibraryRepository;
        this.userService = userService;
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

    /** 当前请求用户可见的媒体库：管理员/后台见全部，受限用户仅见授权且启用的库。 */
    public List<MediaLibrary> getVisibleLibraries() {
        Long userId = UserContext.currentUserIdOrNull();
        if (!isRestrictedUser(userId)) {
            return getAllLibraries();
        }
        List<Long> allowedIds = getAllowedLibraryIds(userId);
        return repository.findAllByOrderBySortOrderAsc().stream()
                .filter(lib -> allowedIds.contains(lib.getId()))
                .toList();
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
        if (updated.getEnableScraping() != null) library.setEnableScraping(updated.getEnableScraping());
        if (updated.getIsAdult() != null) library.setIsAdult(updated.getIsAdult());
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

    public List<Long> getEnabledLibraryIds() {
        return getEnabledLibraries().stream()
                .map(MediaLibrary::getId)
                .collect(Collectors.toList());
    }

    /**
     * 当前请求环境下用户可访问的媒体库 ID（取两者交集）：
     * <ul>
     *   <li>ADMIN 或认证关闭的匿名档案：全部启用库</li>
     *   <li>普通用户：被分配的库 ∩ 启用库；未分配则空</li>
     *   <li>无 Web 请求（后台扫描等）：全部启用库</li>
     * </ul>
     */
    public List<Long> getAllowableLibraryIds() {
        Long userId = UserContext.currentUserIdOrNull();
        if (userId == null) {
            return getEnabledLibraryIds();
        }
        return getAllowedLibraryIds(userId);
    }

    public List<Long> getAllowedLibraryIds(Long userId) {
        if (userId == null || userId == UserContext.ANONYMOUS_ID || userService.isAdmin(userId)) {
            return getEnabledLibraryIds();
        }
        List<Long> enabledIds = getEnabledLibraryIds();
        return userLibraryRepository.findByUserId(userId).stream()
                .map(UserLibrary::getLibraryId)
                .filter(enabledIds::contains)
                .toList();
    }

    /**
     * 当前请求是否为「受限用户」：普通账号（非 ADMIN、非匿名、有 Web 请求）。
     * 受限用户只能看到被分配库内的内容，libraryId 为空的游离内容一律不可见。
     */
    public boolean isRestrictedCurrentUser() {
        Long userId = UserContext.currentUserIdOrNull();
        return isRestrictedUser(userId);
    }

    /** 指定用户是否为受限用户：普通账号（非 ADMIN、非匿名）。 */
    public boolean isRestrictedUser(Long userId) {
        return userId != null && userId != UserContext.ANONYMOUS_ID && !userService.isAdmin(userId);
    }

    /** 当前用户是否可访问某媒体库（受限用户必须已授权且库已启用）。 */
    public boolean isVisibleToCurrentUser(Long libraryId) {
        if (!isRestrictedCurrentUser()) return true;
        return libraryId != null && getAllowableLibraryIds().contains(libraryId);
    }

    /** 管理员为指定用户分配可访问的媒体库（幂等替换）。 */
    @Transactional
    public void assignLibraries(Long userId, List<Long> libraryIds) {
        userService.getUser(userId);
        List<Long> target = libraryIds == null ? List.of() : libraryIds.stream().distinct().toList();

        List<Long> current = userLibraryRepository.findByUserId(userId).stream()
                .map(UserLibrary::getLibraryId)
                .toList();

        for (Long addId : target) {
            if (!current.contains(addId)) {
                getLibraryById(addId);
                userLibraryRepository.save(UserLibrary.builder()
                        .userId(userId).libraryId(addId).build());
            }
        }
        for (Long removeId : current) {
            if (!target.contains(removeId)) {
                userLibraryRepository.deleteByUserIdAndLibraryId(userId, removeId);
            }
        }
        log.info("[LibraryGrant] user={} libraries={}", userId, target);
    }

    /** 用户已分配的媒体库 ID（不做启用态过滤）。 */
    public List<Long> getAssignedLibraryIds(Long userId) {
        return userLibraryRepository.findByUserId(userId).stream()
                .map(UserLibrary::getLibraryId)
                .toList();
    }

    public List<String> getEnabledPaths() {
        return getEnabledLibraries().stream()
                .map(MediaLibrary::getPath)
                .collect(Collectors.toList());
    }

    public boolean isPathInEnabledLibrary(String filePath) {
        if (filePath == null) return false;
        return getEnabledPaths().stream()
                .anyMatch(path -> filePath.startsWith(path));
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
