package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import com.fryfrog.hub.video.service.TranscodingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 音乐扫描服务：遍历音乐资源库，用 ffprobe 读取标签建库（歌手/专辑/单曲），
 * 关联目录封面（cover.jpg / artist.jpg）与歌词（.lrc）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MusicScanService {

    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus",
            "wma", "ape", "mpc", "wv", "alac", "aif", "aiff", "mka", "webm", "dsf", "dff");

    private static final String UNKNOWN_ALBUM = "未知专辑";

    private final MusicArtistRepository artistRepository;
    private final MusicAlbumRepository albumRepository;
    private final MusicSongRepository songRepository;
    private final MusicCleanupService cleanupService;
    private final TranscodingService transcodingService;
    private final ScrapeProgressService progressService;
    private final MusicTagReaderService tagReaderService;

    /**
     * 扫描目录并批量入库。
     * synchronized 串行化扫描：手动扫描与周期扫描并发时，歌手/专辑的
     * 「查无则建」check-then-insert 会竞态产生重复记录。
     */
    public synchronized List<MusicSong> scanAndSave(String directoryPath, Long libraryId) {
        long startTime = System.currentTimeMillis();
        log.info("[MusicScan] Start scanning: {} (libraryId={})", directoryPath, libraryId);
        String module = "music-scan:" + (libraryId != null ? libraryId : "all");

        // 清理失效记录（文件已不存在的单曲及其空歌手/专辑）
        cleanupService.cleanupInvalidRecords();

        List<Path> audioFiles = collectAudioFiles(directoryPath);
        if (audioFiles.isEmpty()) {
            progressService.start(module, 0);
            progressService.finish(module);
            log.info("[MusicScan] No audio files found in: {}", directoryPath);
            return List.of();
        }
        progressService.start(module, audioFiles.size());

        int saved = 0;
        for (Path path : audioFiles) {
            try {
                saveSong(path, libraryId);
                saved++;
                progressService.advance(module, path.getFileName().toString(), true);
            } catch (Exception e) {
                log.warn("[MusicScan] Failed to process {}: {}", path.getFileName(), e.getMessage(), e);
                progressService.advance(module, path.getFileName().toString(), false);
            }
        }
        progressService.finish(module);
        log.info("[MusicScan] Done: {} songs in {}ms (dir={})", saved, System.currentTimeMillis() - startTime, directoryPath);
        return songRepository.findByLibraryIdIn(List.of(libraryId));
    }

    public List<Path> collectAudioFiles(String directoryPath) {
        Path dir = Paths.get(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        int dot = name.lastIndexOf('.');
                        return dot > 0 && SUPPORTED_FORMATS.contains(name.substring(dot + 1));
                    })
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    /** 单曲入库：解析标签 → upsert 歌手/专辑 → upsert 单曲。 */
    public MusicSong saveSong(Path path, Long libraryId) {
        String absolutePath = path.toAbsolutePath().normalize().toString();
        MusicSong existing = songRepository.findByFilePath(absolutePath).orElse(null);
        if (existing != null && !isChanged(existing, path)) {
            log.info("[MusicScan] Metadata unchanged; checking cover: {}", absolutePath);
            ensureLyrics(existing, absolutePath, path.getParent());
            ensureAlbumCover(existing.getAlbum());
            return existing;
        }

        Map<String, Object> info = transcodingService.probeAudioInfo(absolutePath);
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) info.getOrDefault("tags", Map.of());

        // jaudiotagger 直读标签（绕过 ffprobe 的 UTF-8 净化），优先于 ffprobe 值
        Map<String, String> direct = tagReaderService.readTags(path.toFile());
        String dTitle = sanitizeTag(direct.get("title"));
        if (dTitle != null) tags.put("title", dTitle);
        String dArtist = sanitizeTag(direct.get("artist"));
        if (dArtist != null) tags.put("artist", dArtist);
        String dAlbum = sanitizeTag(direct.get("album"));
        if (dAlbum != null) tags.put("album", dAlbum);
        String dAlbumArtist = sanitizeTag(direct.get("albumArtist"));
        if (dAlbumArtist != null && tags.get("album_artist") == null) tags.put("album_artist", dAlbumArtist);
        if (tags.get("track") == null && direct.get("track") != null) tags.put("track", direct.get("track"));
        if (tags.get("disc") == null && direct.get("disc") != null) tags.put("disc", direct.get("disc"));
        if (tags.get("genre") == null && direct.get("genre") != null) tags.put("genre", direct.get("genre"));
        if (tags.get("year") == null && direct.get("year") != null) tags.put("year", direct.get("year"));

        // 目录推断：root/歌手/专辑/曲目.ext
        Path parent = path.getParent();
        String dirAlbum = parent != null ? parent.getFileName().toString() : null;
        String dirArtist = parent != null && parent.getParent() != null
                ? parent.getParent().getFileName().toString() : null;

        String title = firstNonBlank(sanitizeTag(tags.get("title")), stripExtension(path.getFileName().toString()));
        String artistName = firstNonBlank(sanitizeTag(tags.get("artist")), sanitizeTag(tags.get("album_artist")), dirArtist, "未知歌手");
        String albumName = firstNonBlank(sanitizeTag(tags.get("album")), dirAlbum, UNKNOWN_ALBUM);

        // 兜底：文件名「歌手-标题」解析。标签缺失/乱码回退后仍不可用时，
        // 从文件名提取（如 周杰伦-蒲公英的约定.wav → 周杰伦 / 蒲公英的约定），
        // 避免根目录散落文件被推断成 dirArtist=media-library、dirAlbum=music。
        String[] fromFileName = parseArtistTitleFromFileName(stripExtension(path.getFileName().toString()));
        if (fromFileName != null) {
            if ("未知歌手".equals(artistName) || UNKNOWN_ALBUM.equals(albumName) && dirAlbum == null) {
                artistName = fromFileName[0];
            }
            if (title.equals(stripExtension(path.getFileName().toString())) && fromFileName[1] != null) {
                // 标题来自文件名整体时，优先取「-」右侧部分
                title = fromFileName[1];
            }
        }

        MusicArtist artist = getOrCreateArtist(artistName, libraryId, parent != null ? parent.getParent() : null);
        MusicAlbum album = getOrCreateAlbum(albumName, artist, tags, libraryId, parent);
        ensureAlbumCover(album);

        MusicSong song = existing != null ? existing : new MusicSong();
        song.setTitle(title);
        song.setArtistName(artistName);
        song.setAlbumName(albumName);
        song.setArtist(artist);
        song.setAlbum(album);
        song.setTrackNumber(parseInt(tags.get("track"), tags.get("tracknumber")));
        song.setDiscNumber(parseInt(tags.get("disc"), tags.get("discnumber")));
        song.setDurationSeconds(toDouble(info.get("duration")));
        song.setBitRate(toIntKilobits(info.get("bitrate")));
        song.setSampleRate(toInt(info.get("sampleRate")));
        song.setFormat(formatName(absolutePath));
        song.setGenre(firstNonBlank(tags.get("genre"), album.getGenre()));
        song.setYear(parseYear(tags.get("date"), tags.get("year")));
        song.setFilePath(absolutePath);
        song.setFileSize(path.toFile().length());
        String lyricsContent = extractEmbeddedLyrics(tags);
        String lyricsPath = findLyrics(parent, path);
        if (lyricsContent != null && !lyricsContent.isBlank()) {
            log.info("[MusicLyrics] Embedded lyrics found: audio={}, length={}",
                    absolutePath, lyricsContent.length());
        } else {
            log.info("[MusicLyrics] No embedded lyrics found: audio={}", absolutePath);
        }
        if (lyricsPath != null) {
            log.info("[MusicLyrics] Sidecar lyrics found: audio={}, path={}", absolutePath, lyricsPath);
        } else {
            log.info("[MusicLyrics] No sidecar .lrc found: audio={}", absolutePath);
        }
        song.setLyricsPath(lyricsPath);
        song.setLyricsContent(lyricsContent);
        if (song.getLibraryId() == null) {
            song.setLibraryId(libraryId);
        }
        return songRepository.save(song);
    }

    /**
     * 专辑封面策略：
     * 1. 目录图片文件优先（约定名 / 专辑同名 / 任意图片，支持 webp）——外部刮削工具产物
     * 2. 无目录图时，用 jaudiotagger 直读音频内嵌封面（music-tag-web 可写入文件内部），
     *  内嵌封面不落地缓存文件，封面请求时由 MusicController 直读音频内该专辑的首个曲目嵌入图。
     *  此处仅清理历史 .metadata/music-covers 遗留引用（无需在文件系统写任何东西）。
     */
    private void ensureAlbumCover(MusicAlbum album) {
        if (album == null) {
            log.warn("[MusicCover] Cannot check cover: song has no album");
            return;
        }
        String current = album.getCoverArtPath();
        if (current == null || !current.contains(".metadata/music-covers")) {
            return;
        }
        album.setCoverArtPath(null);
        albumRepository.save(album);
        log.info("[MusicCover] Cleared legacy embedded cover cache reference: albumId={}", album.getId());
    }

    private void ensureLyrics(MusicSong song, String audioPath, Path songDir) {
        Map<String, Object> info = transcodingService.probeAudioInfo(audioPath);
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) info.getOrDefault("tags", Map.of());
        String lyricsContent = extractEmbeddedLyrics(tags);
        String lyricsPath = findLyrics(songDir, Paths.get(audioPath));

        if (lyricsContent != null && !lyricsContent.isBlank()) {
            log.info("[MusicLyrics] Embedded lyrics found: audio={}, length={}",
                    audioPath, lyricsContent.length());
        } else {
            log.info("[MusicLyrics] No embedded lyrics found: audio={}", audioPath);
        }
        if (lyricsPath != null) {
            log.info("[MusicLyrics] Sidecar lyrics found: audio={}, path={}", audioPath, lyricsPath);
        } else {
            log.info("[MusicLyrics] No sidecar .lrc found: audio={}", audioPath);
        }

        if (!Objects.equals(song.getLyricsContent(), lyricsContent)
                || !Objects.equals(song.getLyricsPath(), lyricsPath)) {
            song.setLyricsContent(lyricsContent);
            song.setLyricsPath(lyricsPath);
            songRepository.save(song);
            log.info("[MusicLyrics] Lyrics metadata updated: songId={}, audio={}", song.getId(), audioPath);
        }
    }

    private MusicArtist getOrCreateArtist(String name, Long libraryId, Path artistDir) {
        return artistRepository.findFirstByNameAndLibraryId(name, libraryId)
                .orElseGet(() -> {
                    MusicArtist artist = new MusicArtist();
                    artist.setName(name);
                    artist.setSortName(sortName(name));
                    artist.setLibraryId(libraryId);
                    if (artistDir != null) {
                        Path cover = findCover(artistDir, name);
                        if (cover == null) cover = findAnyCover(artistDir);
                        if (cover != null) {
                            log.info("[MusicCover] Artist directory cover found: artist={}, path={}", name, cover);
                            artist.setCoverArtPath(cover.toString());
                        }
                    }
                    return artistRepository.save(artist);
                });
    }

    private MusicAlbum getOrCreateAlbum(String title, MusicArtist artist, Map<String, String> tags, Long libraryId, Path albumDir) {
        String artistName = artist != null ? artist.getName() : "未知歌手";
        return albumRepository.findFirstByTitleAndArtistNameAndLibraryId(title, artistName, libraryId)
                .orElseGet(() -> {
                    MusicAlbum album = new MusicAlbum();
                    album.setTitle(title);
                    album.setArtist(artist);
                    album.setArtistName(artistName);
                    album.setLibraryId(libraryId);
                    album.setGenre(firstNonBlank(tags.get("genre"), null));
                    album.setYear(parseYear(tags.get("date"), tags.get("year")));
                    // 封面由 ensureAlbumCover 统一通过内嵌直读设置，此处不预设目录封面
                    return albumRepository.save(album);
                });
    }

    private boolean isChanged(MusicSong song, Path path) {
        long size = path.toFile().length();
        if (!Objects.equals(song.getFileSize(), size)) return true;
        // 存量乱码记录强制重扫，让新标签修复逻辑覆盖：
        // - U+FFFD：ffprobe 净化产物
        // - 「锟」：GBK 误还原（锟斤拷）的标志性字符，真实歌名几乎不会出现
        if (isMojibake(song.getTitle()) || isMojibake(song.getArtistName()) || isMojibake(song.getAlbumName())) {
            log.info("[MusicScan] Mojibake metadata detected, forcing rescan: {}", path.getFileName());
            return true;
        }
        return false;
    }

    private static boolean isMojibake(String s) {
        return s != null && (s.contains("\uFFFD") || s.contains("锟"));
    }

    private String findLyrics(Path songDir, Path songPath) {
        if (songDir == null) return null;
        try (Stream<Path> files = Files.list(songDir)) {
            String base = stripExtension(songPath.getFileName().toString());
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase())
                    .filter(n -> n.endsWith(".lrc"))
                    .sorted()
                    .map(n -> songDir.resolve(n).toString())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractEmbeddedLyrics(Map<String, String> tags) {
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey() == null
                    ? ""
                    : entry.getKey().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
            if (key.equals("lyrics") || key.contains("lyrics") || key.equals("uslt")
                    || key.equals("©lyr") || key.equals("lyric")) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    /** 支持的封面图片扩展名（含 music-tag-web 刮削产物 webp） */
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /**
     * 查找专辑目录封面，优先级：
     * 1. 约定名 cover/folder/front/album.{jpg,jpeg,png,webp}
     * 2. 专辑同名文件（music-tag-web 刮削产物：{专辑名}.webp/jpg/png）
     * 3. 目录下任意图片文件兜底
     */
    private Path findCover(Path dir, String albumTitle) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        for (String base : new String[]{"cover", "folder", "front", "album"}) {
            for (String ext : COVER_EXTENSIONS) {
                Path candidate = dir.resolve(base + "." + ext);
                if (Files.exists(candidate)) return candidate;
            }
        }
        // 专辑同名封面：{专辑名}.{ext}（大小写不敏感）
        if (albumTitle != null && !albumTitle.isBlank()) {
            try (Stream<Path> files = Files.list(dir)) {
                String want = albumTitle.trim();
                return files.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            if (dot <= 0) return false;
                            String ext = name.substring(dot + 1).toLowerCase();
                            if (!COVER_EXTENSIONS.contains(ext)) return false;
                            return stripExtension(name).equalsIgnoreCase(want);
                        })
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 兜底：目录下任意图片文件 */
    private Path findAnyCover(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        int dot = n.lastIndexOf('.');
                        return dot > 0 && COVER_EXTENSIONS.contains(n.substring(dot + 1));
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatName(String path) {
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        return dot > 0 ? lower.substring(dot + 1).toUpperCase() : "UNKNOWN";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 从「歌手-标题」型文件名解析（首个 - 分隔）。
     * 返回 [artist, title]；不含分隔符或任一侧为空返回 null。
     */
    private static String[] parseArtistTitleFromFileName(String base) {
        if (base == null) return null;
        int idx = base.indexOf('-');
        if (idx <= 0 || idx >= base.length() - 1) return null;
        String artist = base.substring(0, idx).trim();
        String title = base.substring(idx + 1).trim();
        if (artist.isEmpty() || title.isEmpty()) return null;
        return new String[]{artist, title};
    }

    private static String sortName(String name) {
        if (name == null) return null;
        return name.replaceFirst("^(?i)(The|A|An)\\s+", "");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /**
     * 标签清洗：若标签值由大量替换符（�）/问号/菱形占位组成且几乎无可读字符，
     * 视为乱码返回 null，让调用方回退到下一优先级（如文件名）。
     */
    private static String sanitizeTag(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int total = trimmed.length();
        long bad = trimmed.chars().filter(c ->
                c == '\uFFFD' || c == '?' || c == '\u25C6' || c == '\u25C7' || c == '\u00A4').count();
        // 超过一半是占位符即视为无效标签
        if (total > 0 && bad * 2 >= total) {
            return null;
        }
        return trimmed;
    }

    private static Integer parseInt(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                // 注意：Java 中 "/".split("/") 返回空数组（[]），需防御
                String[] parts = v.trim().split("/");
                if (parts.length == 0) continue;
                try {
                    return Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static Integer parseYear(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(v);
                if (m.find()) {
                    try {
                        return Integer.parseInt(m.group());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer toInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Integer toIntKilobits(Object value) {
        Long bits = value instanceof Number n ? n.longValue() : null;
        return bits != null && bits > 0 ? (int) (bits / 1000) : null;
    }
}
