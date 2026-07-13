package com.fryfrog.hub.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.util.FFmpegBinaryExtractor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaInfoService {

    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${video.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @PostConstruct
    public void init() {
        if ("ffprobe".equals(ffprobePath) && "ffmpeg".equals(ffmpegPath)) {
            FFmpegBinaryExtractor.BinaryPaths bundled = FFmpegBinaryExtractor.extract();
            if (bundled != null) {
                this.ffprobePath = bundled.ffprobe();
                this.ffmpegPath = bundled.ffmpeg();
                log.info("FFmpeg 已就绪（内嵌版本）");
                return;
            }
        }

        if (isCommandAvailable(ffprobePath)) {
            log.info("FFmpeg 已就绪（系统版本）: {}", ffprobePath);
        } else {
            log.warn("未找到 FFmpeg，视频分析和字幕功能将不可用");
        }
    }

    private boolean isCommandAvailable(String command) {
        try {
            String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{"cmd", "/c", command, "-version"}
                    : new String[]{command, "-version"};
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> analyzeFile(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        List<String> command = new ArrayList<>();
        command.add(ffprobePath);
        command.add("-v");
        command.add("quiet");
        command.add("-print_format");
        command.add("json");
        command.add("-show_format");
        command.add("-show_streams");
        command.add(filePath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (var is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffprobe timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("ffprobe failed with exit code: " + process.exitValue());
        }

        return objectMapper.readValue(output, Map.class);
    }

    public MediaInfo extractMediaInfo(String filePath) throws Exception {
        Map<String, Object> probeResult = analyzeFile(filePath);
        MediaInfo info = new MediaInfo();

        JsonNode format = objectMapper.valueToTree(probeResult).path("format");
        if (!format.isMissingNode()) {
            if (format.has("duration")) {
                double durationSec = format.path("duration").asDouble();
                info.durationSeconds = durationSec;
                info.durationMinutes = (int) (durationSec / 60);
            }
            if (format.has("bit_rate")) {
                info.bitrateKbps = (int) (format.path("bit_rate").asLong() / 1000);
            }
            info.formatName = format.path("format_name").asText(null);
            info.formatLongName = format.path("format_long_name").asText(null);
        }

        JsonNode streams = objectMapper.valueToTree(probeResult).path("streams");
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                String codecType = stream.path("codec_type").asText("");
                switch (codecType) {
                    case "video":
                        info.videoCodec = stream.path("codec_name").asText(null);
                        info.videoCodecLong = stream.path("codec_long_name").asText(null);
                        info.videoProfile = stream.path("profile").asText(null);
                        info.pixFmt = stream.path("pix_fmt").asText(null);
                        String dar = stream.path("display_aspect_ratio").asText(null);
                        if (dar != null && !dar.equals("0:1")) {
                            info.displayAspectRatio = dar;
                        }
                        int width = stream.path("width").asInt(0);
                        int height = stream.path("height").asInt(0);
                        if (width > 0 && height > 0) {
                            info.resolution = width + "x" + height;
                        }
                        String rFrameRate = stream.path("r_frame_rate").asText("0/1");
                        info.frameRate = parseFrameRate(rFrameRate);
                        break;
                    case "audio":
                        if (info.audioCodec == null) {
                            info.audioCodec = stream.path("codec_name").asText(null);
                            info.audioCodecLong = stream.path("codec_long_name").asText(null);
                            info.audioChannels = stream.path("channels").asInt(0);
                            info.audioSampleRate = stream.path("sample_rate").asText(null);
                            info.audioChannelLayout = stream.path("channel_layout").asText(null);
                        }
                        break;
                    case "subtitle":
                        SubtitleInfo sub = new SubtitleInfo();
                        sub.index = stream.path("index").asInt();
                        sub.codec = stream.path("codec_name").asText(null);
                        sub.language = stream.path("tags").path("language").asText(null);
                        sub.title = stream.path("tags").path("title").asText(null);
                        sub.defaultStream = "yes".equals(stream.path("disposition").path("default").asText("0"));
                        info.subtitles.add(sub);
                        break;
                }
            }
        }

        return info;
    }

    private double parseFrameRate(String rFrameRate) {
        try {
            String[] parts = rFrameRate.split("/");
            if (parts.length == 2) {
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                if (den > 0) {
                    return Math.round(num / den * 100.0) / 100.0;
                }
            }
            return Double.parseDouble(rFrameRate);
        } catch (Exception e) {
            return 0;
        }
    }

    public void updateVideoMediaInfo(Video video) {
        try {
            MediaInfo info = extractMediaInfo(video.getFilePath());

            video.setVideoCodec(info.videoCodec);
            video.setVideoProfile(info.videoProfile);
            video.setPixFmt(info.pixFmt);
            video.setDisplayAspectRatio(info.displayAspectRatio);
            video.setAudioCodec(info.audioCodec);
            video.setAudioChannelLayout(info.audioChannelLayout);
            video.setResolution(info.resolution);
            video.setFrameRate(info.frameRate);
            video.setBitrateKbps(info.bitrateKbps);
            if (video.getDurationMinutes() == null || video.getDurationMinutes() == 0) {
                video.setDurationMinutes(info.durationMinutes);
            }
            if (video.getDurationSeconds() == null || video.getDurationSeconds() == 0) {
                video.setDurationSeconds(info.durationSeconds);
            }

            videoRepository.save(video);
            log.debug("Updated media info for: {}", video.getFileName());
        } catch (Exception e) {
            log.warn("Failed to analyze media info for {}: {}", video.getFileName(), e.getMessage());
        }
    }

    private static final java.util.Set<String> SUBTITLE_EXTENSIONS = java.util.Set.of(
            ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup"
    );

    // ASS 矢量绘图命令：以 m x y 开头（move to），后跟 l/b/c/s 曲线/直线命令
    private static final Pattern ASS_DRAW_PATTERN = Pattern.compile(
            "^m\\s+-?\\d+\\s+-?\\d+"
    );

    public List<Map<String, Object>> getSubtitleTracks(String filePath) throws Exception {
        MediaInfo info = extractMediaInfo(filePath);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SubtitleInfo sub : info.subtitles) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("index", sub.index);
            map.put("codec", sub.codec);
            map.put("language", sub.language);
            map.put("title", sub.title);
            map.put("default", sub.defaultStream);
            result.add(map);
        }
        return result;
    }

    public String extractSubtitleAsVtt(String filePath, int streamIndex) throws Exception {
        List<String> command = List.of(
                ffmpegPath, "-y", "-i", filePath,
                "-map", "0:" + streamIndex,
                "-c:s", "webvtt",
                "-f", "webvtt",
                "pipe:1"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        byte[] stdout;
        try (var is = process.getInputStream()) {
            stdout = is.readAllBytes();
        }
        try (var es = process.getErrorStream()) {
            es.readAllBytes();
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffmpeg subtitle conversion timed out");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("ffmpeg subtitle conversion failed with exit code: " + process.exitValue());
        }
        return cleanVttContent(new String(stdout, StandardCharsets.UTF_8));
    }

    public List<Map<String, Object>> getExternalSubtitles(String videoPath) {
        Path dir = Path.of(videoPath).getParent();
        String baseName = getBaseName(Path.of(videoPath).getFileName().toString());
        List<Map<String, Object>> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return result;

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        return name.startsWith(baseName) && SUBTITLE_EXTENSIONS.stream()
                                .anyMatch(ext -> name.toLowerCase().endsWith(ext));
                    })
                    .sorted()
                    .forEach(f -> {
                        String name = f.getFileName().toString();
                        String ext = name.substring(name.lastIndexOf('.')).toLowerCase();
                        result.add(Map.of(
                                "fileName", name,
                                "ext", ext,
                                "path", f.toString(),
                                "language", extractLanguageFromName(name, baseName)
                        ));
                    });
        } catch (IOException ignored) {
        }
        return result;
    }

    public String convertExternalSubtitleToVtt(String subtitlePath) throws Exception {
        List<String> command = List.of(
                ffmpegPath, "-y", "-i", subtitlePath,
                "-c:s", "webvtt",
                "-f", "webvtt",
                "pipe:1"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        byte[] stdout;
        try (var is = process.getInputStream()) {
            stdout = is.readAllBytes();
        }
        try (var es = process.getErrorStream()) {
            es.readAllBytes();
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffmpeg subtitle conversion timed out");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("ffmpeg subtitle conversion failed with exit code: " + process.exitValue());
        }
        return cleanVttContent(new String(stdout, StandardCharsets.UTF_8));
    }

    /** 过滤绘图命令 + 合并重叠的卡拉OK逐字cue */
    static String cleanVttContent(String vtt) {
        vtt = vtt.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        List<Cue> cues = parseCues(vtt);
        // 过滤绘图命令
        cues.removeIf(c -> ASS_DRAW_PATTERN.matcher(c.text.trim()).find());
        // 按开始时间排序
        cues.sort(Comparator.comparingLong(c -> c.startMs));
        // 合并重叠 cue
        List<Cue> merged = new ArrayList<>();
        for (Cue cue : cues) {
            if (!merged.isEmpty() && cue.startMs < merged.getLast().endMs) {
                Cue last = merged.getLast();
                last.endMs = Math.max(last.endMs, cue.endMs);
                String trimmed = cue.text.trim();
                if (!trimmed.isEmpty() && !last.text.contains(trimmed)) {
                    last.text = (last.text.trim() + trimmed).strip();
                }
            } else {
                merged.add(new Cue(cue.startMs, cue.endMs, cue.text.trim()));
            }
        }
        // 输出
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (Cue c : merged) {
            out.append(formatMs(c.startMs)).append(" --> ").append(formatMs(c.endMs)).append("\n");
            out.append(c.text).append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static List<Cue> parseCues(String vtt) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = vtt.split("\n\n");
        for (String block : blocks) {
            if (block.isBlank()) continue;
            String[] lines = block.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    String[] parts = lines[i].split("-->");
                    if (parts.length == 2) {
                        long start = parseMs(parts[0].trim());
                        long end = parseMs(parts[1].trim().split("\\s")[0]);
                        StringBuilder text = new StringBuilder();
                        for (int j = i + 1; j < lines.length; j++) {
                            if (!lines[j].isBlank()) {
                                if (!text.isEmpty()) text.append("\n");
                                text.append(lines[j]);
                            }
                        }
                        if (!text.isEmpty() && start < end) {
                            cues.add(new Cue(start, end, text.toString()));
                        }
                    }
                    break;
                }
            }
        }
        return cues;
    }

    private static long parseMs(String ts) {
        String[] hms = ts.split(":");
        long ms = 0;
        if (hms.length == 3) {
            ms += Long.parseLong(hms[0]) * 3600000;
            ms += Long.parseLong(hms[1]) * 60000;
            ms += parseSeconds(hms[2]);
        } else if (hms.length == 2) {
            ms += Long.parseLong(hms[0]) * 60000;
            ms += parseSeconds(hms[1]);
        } else if (hms.length == 1) {
            ms += parseSeconds(hms[0]);
        }
        return ms;
    }

    private static long parseSeconds(String s) {
        String[] parts = s.split("\\.");
        long ms = Long.parseLong(parts[0]) * 1000;
        if (parts.length > 1) {
            ms += Long.parseLong(parts[1].substring(0, Math.min(3, parts[1].length())));
        }
        return ms;
    }

    private static String formatMs(long ms) {
        long h = ms / 3600000; ms %= 3600000;
        long m = ms / 60000; ms %= 60000;
        long s = ms / 1000; ms %= 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
    }

    private static class Cue {
        long startMs, endMs;
        String text;
        Cue(long start, long end, String text) {
            this.startMs = start; this.endMs = end; this.text = text;
        }
    }

    private String getBaseName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String extractLanguageFromName(String fileName, String baseName) {
        String withoutBase = fileName.substring(baseName.length());
        if (withoutBase.startsWith(".")) {
            String langPart = withoutBase.substring(1);
            int dotIndex = langPart.indexOf('.');
            if (dotIndex > 0) {
                return langPart.substring(0, dotIndex).toLowerCase();
            }
        }
        return "unknown";
    }

    private static final Set<String> INCOMPATIBLE_AUDIO_CODECS = Set.of(
            "eac3", "dts", "truehd", "dca", "mlp"
    );

    public boolean isAudioIncompatible(String filePath) throws Exception {
        MediaInfo info = extractMediaInfo(filePath);
        if (info.audioCodec == null) return false;
        return INCOMPATIBLE_AUDIO_CODECS.stream()
                .anyMatch(c -> info.audioCodec.toLowerCase().contains(c));
    }

    public String getIncompatibleCodec(String filePath) throws Exception {
        MediaInfo info = extractMediaInfo(filePath);
        if (info.audioCodec == null) return null;
        String codec = info.audioCodec.toLowerCase();
        for (String c : INCOMPATIBLE_AUDIO_CODECS) {
            if (codec.contains(c)) return c;
        }
        return null;
    }

    /**
     * 将不兼容的音频编码转为 AAC
     * @return 转码后的文件路径，如果已兼容则返回 null
     */
    public String transcodeAudio(String filePath) throws Exception {
        String incompatibleCodec = getIncompatibleCodec(filePath);
        if (incompatibleCodec == null) {
            return null;
        }

        File inputFile = new File(filePath);
        String baseName = getBaseName(inputFile.getName());
        String ext = filePath.substring(filePath.lastIndexOf('.'));
        String outputPath = inputFile.getParent() + File.separator + baseName + "_transcoded" + ext;

        List<String> command = List.of(
                ffmpegPath, "-y", "-i", filePath,
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                outputPath
        );

        log.info("开始音频转码: {} ({} → aac)", inputFile.getName(), incompatibleCodec);
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        try (var es = process.getErrorStream()) {
            es.readAllBytes();
        }

        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("音频转码超时");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("音频转码失败，exit code: " + process.exitValue());
        }

        log.info("音频转码完成: {}", outputPath);
        return outputPath;
    }

    public static class MediaInfo {
        public double durationSeconds;
        public int durationMinutes;
        public int bitrateKbps;
        public String formatName;
        public String formatLongName;
        public String videoCodec;
        public String videoCodecLong;
        public String videoProfile;
        public String pixFmt;
        public String displayAspectRatio;
        public String resolution;
        public double frameRate;
        public String audioCodec;
        public String audioCodecLong;
        public int audioChannels;
        public String audioSampleRate;
        public String audioChannelLayout;
        public List<SubtitleInfo> subtitles = new ArrayList<>();
    }

    public static class SubtitleInfo {
        public int index;
        public String codec;
        public String language;
        public String title;
        public boolean defaultStream;
    }
}
