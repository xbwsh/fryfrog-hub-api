package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.audiobook.util.NaturalOrderComparator;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将旧扁平格式的有声书自动升级为 root/作品名/作品名第一季/001.ext 结构。
 *
 * 旧格式（库根直接放音频，多部作品混在一起也会被扫描聚成一本）：
 *   /有声书/剑来001.mp3、剑来002.mp3、三体001.mp3 ...
 * 新格式：
 *   /有声书/剑来/剑来第一季/001.mp3 ...
 *   /有声书/三体/三体第一季/001.mp3 ...
 *
 * 规则：
 * - 仅处理 bookPath 等于库根的扁平书；已分组（root/作品/分卷/）的书跳过
 * - 按文件名前缀拆分作品（"剑来001" → 作品"剑来"）；任一文件无前缀模式则整书跳过
 * - 同组文件按自然序重新编号 001...，分卷目录名 = 作品名 + "第一季"
 * - 单一作品组：原书实体原地改写；多作品组：拆分为多本新书并删除原书
 * - dryRun=true 仅预览；执行先移文件，全部成功后在事务内更新 DB
 *
 * 仅由显式 API 调用（管理员），不参与普通扫描。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudiobookOrganizeService {

    /**
     * 文件名尾部集号（2~4 位数字，分隔符可选）："剑来001"、"剑来 01"。
     * 单独一位数字必须带显式分隔符（"剑来 #1"），避免 "三体2" 这类书名误拆。
     */
    private static final Pattern TAIL_DIGITS = Pattern.compile(
            "^(.+?)?[-_\\s#·]*(\\d{2,4})\\s*$");
    private static final Pattern TAIL_DIGIT_ONE = Pattern.compile(
            "^(.+)[-_\\s#·]+(\\d)\\s*$");

    /** 分卷名序号解析：剑来第一季→1、第2部→2、卷三→3、Part 4→4、剑来 #5→5 */
    private static final Pattern SEASON_PART = Pattern.compile(
            "^.+?(?:第|Season\\s*|S|卷|部|Part\\s*|#)?\\s*([0-9一二三四五六七八九十]+)\\s*(?:季|部|卷|集)?$",
            Pattern.CASE_INSENSITIVE);

    private final AudiobookRepository bookRepository;
    private final AudiobookTrackRepository trackRepository;
    private final MediaLibraryService mediaLibraryService;
    private final TransactionTemplate transactionTemplate;

    public Map<String, Object> organize(Long libraryId, boolean dryRun) {
        MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
        if (!library.isAudiobookType()) {
            throw new IllegalArgumentException("指定资源库不是 AUDIOBOOK 类型");
        }

        Path root = Paths.get(library.getPath()).toAbsolutePath().normalize();
        List<Map<String, Object>> items = new ArrayList<>();
        int movedOrPlanned = 0;
        int unchanged = 0;
        int failed = 0;
        int organizedBooks = 0;
        int skippedBooks = 0;

        List<Audiobook> flatBooks = bookRepository.findByLibraryId(libraryId).stream()
                .filter(b -> isLibraryRoot(root, b.getBookPath()))
                .sorted(Comparator.comparing(Audiobook::getTitle, Comparator.nullsLast(String::compareTo)))
                .toList();

        for (Audiobook book : flatBooks) {
            List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(book.getId());
            Map<String, List<AudiobookTrack>> groups = groupByWork(tracks);
            if (groups == null) {
                // 存在无法识别前缀的文件，保守跳过整书
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bookId", book.getId());
                item.put("book", book.getTitle());
                item.put("status", "skipped");
                item.put("message", "存在无法识别作品前缀的文件（尾部无集号），请手动处理");
                items.add(item);
                skippedBooks++;
                continue;
            }

            List<Map.Entry<String, List<AudiobookTrack>>> entries = new ArrayList<>(groups.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, List<AudiobookTrack>> entry = entries.get(i);
                BookPlan plan = planGroup(entry.getKey(), entry.getValue(), root, i == 0);
                items.addAll(plan.items);
                movedOrPlanned += plan.movedOrPlanned;
                unchanged += plan.unchanged;
                failed += plan.failed;

                if (!dryRun && plan.movedOrPlanned > 0) {
                    executePlan(book, plan);
                    if (plan.failed == 0) organizedBooks++;
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("libraryId", libraryId);
        response.put("dryRun", dryRun);
        response.put("totalBooks", flatBooks.size());
        response.put("movedOrPlanned", movedOrPlanned);
        response.put("unchanged", unchanged);
        response.put("failed", failed);
        if (!dryRun) {
            response.put("organizedGroups", organizedBooks);
            response.put("skippedBooks", skippedBooks);
            response.put("hint", "整理完成后建议重新扫描，以便重建封面与章节");
        }
        response.put("items", items);
        return response;
    }

    /** 按文件名前缀（作品名）分组；任一文件无法识别前缀返回 null。 */
    private Map<String, List<AudiobookTrack>> groupByWork(List<AudiobookTrack> tracks) {
        Map<String, List<AudiobookTrack>> groups = new TreeMap<>();
        for (AudiobookTrack track : tracks) {
            String name = Paths.get(track.getFilePath()).getFileName().toString();
            String work = stripEpisodeOf(name);
            if (work == null || work.isBlank()) {
                return null;
            }
            groups.computeIfAbsent(work, k -> new ArrayList<>()).add(track);
        }
        return groups;
    }

    /** 规划一个作品组的移动清单（第一组复用原书实体）。 */
    private BookPlan planGroup(String work, List<AudiobookTrack> tracks, Path root, boolean firstGroup) {
        BookPlan plan = new BookPlan();
        plan.series = work;
        plan.isFirstGroup = firstGroup;
        plan.newBookDir = root.resolve(safeName(work)).resolve(safeName(work + "第一季"));

        List<AudiobookTrack> ordered = tracks.stream()
                .sorted(Comparator.comparing(t -> Paths.get(t.getFilePath()).getFileName().toString(),
                        NaturalOrderComparator.INSTANCE))
                .toList();
        int episode = 0;
        for (AudiobookTrack track : ordered) {
            TrackMove move = new TrackMove();
            move.track = track;
            move.source = Paths.get(track.getFilePath());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trackId", track.getId());
            item.put("work", work);
            item.put("source", move.source.toString());

            if (!Files.isRegularFile(move.source)) {
                item.put("status", "failed");
                item.put("message", "文件不存在");
                plan.failed++;
                plan.items.add(item);
                continue;
            }

            episode++;
            move.target = plan.newBookDir.resolve(String.format("%03d%s", episode, extension(move.source)));
            item.put("target", move.target.toString());
            if (move.source.equals(move.target)) {
                item.put("status", "unchanged");
                plan.unchanged++;
            } else {
                item.put("status", "planned");
                plan.movedOrPlanned++;
            }
            plan.moves.add(move);
            plan.items.add(item);
        }
        return plan;
    }

    /** 执行：先移文件，再按移动结果在事务内更新/拆分 DB 记录。 */
    private void executePlan(Audiobook originBook, BookPlan plan) {
        for (TrackMove move : plan.moves) {
            if (move.source.equals(move.target)) continue;
            try {
                moveFile(move.source, move.target);
            } catch (Exception e) {
                log.warn("[AudiobookOrganize] Move failed {} -> {}: {}", move.source, move.target, e.getMessage());
                plan.failed++;
                plan.movedOrPlanned--;
            }
        }
        if (plan.failed > 0 && plan.movedOrPlanned <= 0) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> applyBookMove(originBook, plan));
    }

    /** 文件移动后更新 DB：更新 track 路径；按组拆分或改写书实体。 */
    private void applyBookMove(Audiobook originBook, BookPlan plan) {
        // 更新 track：指向新路径并改挂到目标书
        Audiobook targetBook;
        if (plan.isFirstGroup) {
            // 第一个组复用原书实体（原地改写），避免多余插入
            Audiobook managed = bookRepository.findById(originBook.getId()).orElse(null);
            if (managed == null) return;
            managed.setTitle(plan.series);
            managed.setBookPath(plan.newBookDir.toString());
            managed.setSeries(plan.series);
            managed.setSeriesPart(seasonPartOf(plan.newBookDir.getFileName().toString()) != null
                    ? seasonPartOf(plan.newBookDir.getFileName().toString()) : 1);
            targetBook = bookRepository.save(managed);
        } else {
            final Audiobook newBook = Audiobook.builder()
                    .title(plan.series)
                    .bookPath(plan.newBookDir.toString())
                    .libraryId(originBook.getLibraryId())
                    .playType(Audiobook.TYPE_MULTI)
                    .series(plan.series)
                    .seriesPart(seasonPartOf(plan.newBookDir.getFileName().toString()) != null
                            ? seasonPartOf(plan.newBookDir.getFileName().toString()) : 1)
                    .build();
            targetBook = bookRepository.save(newBook);
        }

        List<AudiobookTrack> updated = new ArrayList<>();
        double durationSum = 0;
        long sizeSum = 0;
        for (TrackMove move : plan.moves) {
            AudiobookTrack managed = trackRepository.findById(move.track.getId()).orElse(null);
            if (managed == null) continue;
            boolean moved = !move.source.equals(move.target) && Files.exists(move.target);
            if (moved) {
                managed.setFilePath(move.target.toString());
            }
            managed.setAudiobook(targetBook);
            updated.add(trackRepository.save(managed));
            if (managed.getDurationSeconds() != null) durationSum += managed.getDurationSeconds();
            if (managed.getFileSize() != null) sizeSum += managed.getFileSize();
        }
        targetBook.setTrackCount(updated.size());
        targetBook.setTotalDurationSeconds(durationSum);
        targetBook.setTotalFileSize(sizeSum);
        bookRepository.save(targetBook);

        // 拆分场景：所有组处理完后由最后一个调用方删除原书？——否：各组件独立执行，
        // 原书只在第一组时被复用；多组时原书在第一个组就被改写为第一组，
        // 其余组各自新建书并接管自己的 track。原书不再独立存在。
        log.info("[AudiobookOrganize] Group '{}' organized -> {} ({} tracks)",
                plan.series, plan.newBookDir, updated.size());
    }

    void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /** 文件名去掉尾部集号（"剑来001"→"剑来"、"剑来 #1"→"剑来"），不匹配返回 null。 */
    static String stripEpisodeOf(String title) {
        if (title == null || title.isBlank()) return null;
        String stem = title.strip();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        Matcher m = TAIL_DIGITS.matcher(stem);
        if (m.matches()) {
            String prefix = m.group(1);
            return prefix != null && !prefix.isBlank() ? prefix.strip() : null;
        }
        m = TAIL_DIGIT_ONE.matcher(stem);
        if (m.matches()) {
            return m.group(1).strip();
        }
        return null;
    }

    /** 分卷名序号解析（中文数字支持一~十），不匹配返回 null。 */
    static Integer seasonPartOf(String seasonName) {
        if (seasonName == null || seasonName.isBlank()) return null;
        Matcher m = SEASON_PART.matcher(seasonName.strip());
        if (!m.matches()) return null;
        String num = m.group(1);
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return chineseNumeral(num);
        }
    }

    private static Integer chineseNumeral(String s) {
        return switch (s) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> null;
        };
    }

    private static boolean isLibraryRoot(Path root, String bookPath) {
        if (bookPath == null) return false;
        return Paths.get(bookPath).toAbsolutePath().normalize().equals(root);
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "未知";
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|#]", "_")
                .replaceAll("[\\p{Cntrl}]", "_").strip();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1).stripTrailing();
        return cleaned.isBlank() ? "未知" : cleaned;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase() : "";
    }

    /** 单个文件的移动计划。 */
    private static class TrackMove {
        AudiobookTrack track;
        Path source;
        Path target;
    }

    /** 一个作品组的整理计划（内部聚合）。 */
    private static class BookPlan {
        String series;
        Path newBookDir;
        boolean isFirstGroup;
        final List<TrackMove> moves = new ArrayList<>();
        final List<Map<String, Object>> items = new ArrayList<>();
        int movedOrPlanned;
        int unchanged;
        int failed;
    }
}
