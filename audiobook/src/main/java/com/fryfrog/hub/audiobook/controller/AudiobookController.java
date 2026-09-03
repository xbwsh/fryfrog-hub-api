package com.fryfrog.hub.audiobook.controller;

import com.fryfrog.hub.audiobook.dto.AudiobookDetailDTO;
import com.fryfrog.hub.audiobook.dto.AudiobookListDTO;
import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookChapter;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookChapterRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.audiobook.service.AudiobookProgressService;
import com.fryfrog.hub.audiobook.service.AudiobookScanService;
import com.fryfrog.hub.audiobook.service.AudiobookStreamService;
import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.common.exception.ForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/v1/audiobooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "有声书管理", description = "有声书扫描、播放、进度接口")
public class AudiobookController {

    private final AudiobookRepository bookRepository;
    private final AudiobookTrackRepository trackRepository;
    private final AudiobookChapterRepository chapterRepository;
    private final AudiobookScanService scanService;
    private final AudiobookStreamService streamService;
    private final AudiobookProgressService progressService;
    private final MediaLibraryService mediaLibraryService;
    private final UserService userService;

    private void requireAdmin(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!userService.isAdmin(userId)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    /** 库 ID 可见性过滤：受限用户仅授权库。 */
    private List<Long> allowableIds() {
        return mediaLibraryService.getAllowableLibraryIds();
    }

    private Audiobook requireVisibleBook(Long id) {
        Audiobook book = bookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", id));
        if (book.getLibraryId() != null && !allowableIds().contains(book.getLibraryId())) {
            throw new ResourceNotFoundException("Audiobook", "id", id);
        }
        return book;
    }

    // ── 扫描 ──

    @PostMapping("/scan")
    @Operation(summary = "扫描有声书资源库", description = "扫描指定 AUDIOBOOK 资源库（异步执行），不传 libraryId 时扫描全部")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scan(
            @Parameter(description = "资源库ID，可选") @RequestParam(required = false) Long libraryId,
            HttpServletRequest request) {
        requireAdmin(request);
        if (libraryId != null && !mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", libraryId);
        }
        List<MediaLibrary> libraries = libraryId != null
                ? List.of(mediaLibraryService.getLibraryById(libraryId))
                : mediaLibraryService.getVisibleLibraries().stream()
                        .filter(MediaLibrary::isAudiobookType).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", libraries.size());
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : libraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[AudiobookScan] Failed library '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    // ── 列表 / 详情 ──

    @GetMapping
    @Operation(summary = "有声书列表", description = "返回当前用户可见库中的有声书，支持分页与关键词搜索")
    public ResponseEntity<ApiResponse<PageResponse<AudiobookListDTO>>> list(
            @Parameter(description = "搜索关键词（书名模糊）") @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<Long> allowed = allowableIds();
        if (allowed.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.of(List.of(), page, size, 0)));
        }
        Page<Audiobook> result;
        if (q != null && !q.isBlank()) {
            result = bookRepository.findByLibraryIdInAndTitleContainingIgnoreCase(
                    allowed, q, PageRequest.of(page, size, Sort.by("title").ascending()));
        } else {
            result = bookRepository.findByLibraryIdIn(allowed,
                    PageRequest.of(page, size, Sort.by("title").ascending()));
        }
        Map<Long, AudiobookProgress> progressMap = progressService.getProgressByBookIds(
                userId, result.getContent().stream().map(Audiobook::getId).toList());
        List<AudiobookListDTO> dtos = result.getContent().stream()
                .map(b -> AudiobookListDTO.from(b, progressMap.get(b.getId())))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "有声书详情", description = "返回元数据、音轨/章节与当前用户进度。SINGLE 模式 chapters 为文件内嵌章节；MULTI 模式 tracks 即章节")
    public ResponseEntity<ApiResponse<AudiobookDetailDTO>> detail(
            @Parameter(description = "有声书ID") @PathVariable Long id,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        Audiobook book = requireVisibleBook(id);

        List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(id);
        List<AudiobookDetailDTO.TrackDTO> trackDTOs = tracks.stream()
                .map(AudiobookDetailDTO.TrackDTO::from).toList();

        // 章节统一为全局时间轴：SINGLE 用内嵌章节；MULTI 由音轨累计
        List<AudiobookDetailDTO.ChapterDTO> chapters;
        if (Audiobook.TYPE_SINGLE.equals(book.getPlayType())) {
            List<AudiobookChapter> rows = chapterRepository.findByAudiobook_IdOrderByChapterIndexAsc(id);
            chapters = rows.stream()
                    .map(c -> AudiobookDetailDTO.ChapterDTO.builder()
                            .chapterIndex(c.getChapterIndex())
                            .title(c.getTitle())
                            .startSeconds(c.getStartSeconds())
                            .endSeconds(c.getEndSeconds())
                            .trackIndex(0)
                            .startInTrack(c.getStartSeconds())
                            .build())
                    .toList();
            if (chapters.isEmpty() && !tracks.isEmpty()) {
                Double dur = tracks.get(0).getDurationSeconds();
                chapters = List.of(AudiobookDetailDTO.ChapterDTO.builder()
                        .chapterIndex(0).title(book.getTitle())
                        .startSeconds(0d).endSeconds(dur)
                        .trackIndex(0).startInTrack(0d).build());
            }
        } else {
            chapters = buildGlobalChapters(tracks);
        }

        AudiobookProgress progress = progressService.getProgress(userId, id);
        double globalSeconds = globalSecondsOf(progress, tracks);
        return ResponseEntity.ok(ApiResponse.success(AudiobookDetailDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .narrator(book.getNarrator())
                .series(book.getSeries())
                .seriesPart(book.getSeriesPart())
                .playType(book.getPlayType())
                .coverUrl(book.getCoverUrl())
                .totalDurationSeconds(book.getTotalDurationSeconds())
                .trackCount(book.getTrackCount())
                .tracks(trackDTOs)
                .chapters(chapters)
                .progress(progress != null
                        ? AudiobookDetailDTO.ProgressDTO.from(progress, book, globalSeconds) : null)
                .build()));
    }

    // ── 封面 / 流 ──

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面", description = "返回有声书封面图片")
    public ResponseEntity<FileSystemResource> cover(@PathVariable Long id) {
        Audiobook book = requireVisibleBook(id);
        if (book.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(book.getCoverArtPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(path.toFile()));
    }

    @GetMapping("/tracks/{trackId:\\d+}/stream")
    @Operation(summary = "音轨流播放", description = "支持 Range 请求断点续播")
    public void streamTrack(
            @Parameter(description = "音轨ID") @PathVariable Long trackId,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response) throws Exception {
        AudiobookTrack track = trackRepository.findWithAudiobookById(trackId)
                .orElseThrow(() -> new ResourceNotFoundException("AudiobookTrack", "id", trackId));
        requireVisibleBook(track.getAudiobook().getId());
        File file = new File(track.getFilePath());
        streamService.stream(response, file, track.getFormat(), rangeHeader, null);
    }

    // ── 进度 ──

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存收听进度", description = "body: {trackIndex, positionSeconds}；最后一轨播放超 95% 自动标记听完")
    public ResponseEntity<ApiResponse<AudiobookDetailDTO.ProgressDTO>> saveProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        Integer trackIndex = body.get("trackIndex") instanceof Number n ? n.intValue() : null;
        Double position = body.get("positionSeconds") instanceof Number n ? n.doubleValue() : null;
        AudiobookProgress progress = progressService.updatePosition(userId, id, trackIndex, position);

        List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(id);
        double globalSeconds = globalSecondsOf(progress, tracks);
        return ResponseEntity.ok(ApiResponse.success(
                AudiobookDetailDTO.ProgressDTO.from(progress, bookRepository.findById(id).orElse(null), globalSeconds)));
    }

    @PutMapping("/{id:\\d+}/completed")
    @Operation(summary = "设置听完状态", description = "body: {completed}")
    public ResponseEntity<ApiResponse<Void>> setCompleted(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        boolean completed = Boolean.TRUE.equals(body.get("completed"));
        progressService.setCompleted(userId, id, completed);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "清除收听进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(@PathVariable Long id, HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        progressService.deleteProgress(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 作者聚合 ──

    @GetMapping("/authors")
    @Operation(summary = "作者列表", description = "按可见库聚合作者及著作数，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<Map<String, Object>>>> authors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Object[]> rows = bookRepository.countByAuthor(allowableIds(),
                PageRequest.of(page, size));
        List<Map<String, Object>> content = rows.getContent().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("author", row[0]);
                    item.put("bookCount", ((Number) row[1]).longValue());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(content, page, size, rows.getTotalElements())));
    }

    // ── 工具 ──

    /** MULTI 模式章节：音轨累计为全局时间轴 */
    private List<AudiobookDetailDTO.ChapterDTO> buildGlobalChapters(List<AudiobookTrack> tracks) {
        List<AudiobookDetailDTO.ChapterDTO> chapters = new ArrayList<>();
        double global = 0;
        for (AudiobookTrack track : tracks) {
            double duration = track.getDurationSeconds() != null ? track.getDurationSeconds() : 0;
            chapters.add(AudiobookDetailDTO.ChapterDTO.builder()
                    .chapterIndex(track.getTrackIndex())
                    .title(track.getTitle())
                    .startSeconds(global)
                    .endSeconds(global + duration)
                    .trackIndex(track.getTrackIndex())
                    .startInTrack(0d)
                    .build());
            global += duration;
        }
        return chapters;
    }

    /** 进度换算为全书全局秒数（进度百分比用） */
    private double globalSecondsOf(AudiobookProgress progress, List<AudiobookTrack> tracks) {
        if (progress == null || progress.getTrackIndex() == null) return 0;
        double done = 0;
        for (AudiobookTrack track : tracks) {
            if (track.getTrackIndex() >= progress.getTrackIndex()) break;
            done += track.getDurationSeconds() != null ? track.getDurationSeconds() : 0;
        }
        return done + (progress.getPositionSeconds() != null ? progress.getPositionSeconds() : 0);
    }
}
