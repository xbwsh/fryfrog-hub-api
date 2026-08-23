package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.util.FFmpegBinaryExtractor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TranscodingService {

    @Value("${video.ffmpeg-path:}")
    private String configuredFfmpegPath;

    private String ffmpegPath;
    private String ffprobePath;
    private String libraryDir;
    private boolean ffmpegAvailable = false;

    @PostConstruct
    public void init() {
        if (configuredFfmpegPath != null && !configuredFfmpegPath.isBlank()) {
            ffmpegPath = configuredFfmpegPath;
            ffprobePath = configuredFfmpegPath.replace("ffmpeg", "ffprobe");
            log.info("Using configured FFmpeg: {}", ffmpegPath);
        } else {
            FFmpegBinaryExtractor.BinaryPaths paths = FFmpegBinaryExtractor.extract();
            if (paths != null) {
                ffmpegPath = paths.ffmpeg();
                ffprobePath = paths.ffprobe();
                libraryDir = paths.libraryDir();
                log.info("Using bundled FFmpeg: {}, ffprobe: {}", ffmpegPath, ffprobePath);
            } else {
                ffmpegPath = "ffmpeg";
                ffprobePath = "ffprobe";
                log.warn("No FFmpeg found, trying system PATH");
            }
        }

        ffmpegAvailable = checkFfmpegAvailable();
        if (ffmpegAvailable) {
            log.info("FFmpeg transcoding available: {}", ffmpegPath);
        } else {
            log.warn("FFmpeg not available, transcoding disabled");
        }
    }

    private boolean checkFfmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("FFmpeg check failed: {}", e.getMessage());
            return false;
        }
    }

    private String getLibraryPathEnv() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) return "DYLD_LIBRARY_PATH";
        if (os.contains("win")) return "PATH";
        return "LD_LIBRARY_PATH";
    }

    public boolean isAvailable() {
        return ffmpegAvailable;
    }

    /**
     * 使用 ffprobe 探测音频文件的格式与标签元数据（供音乐扫描建库）。
     * 返回键：duration(秒)、bitrate、format、codec、sampleRate、tags(Map)。
     * 失败或 ffprobe 不可用时返回空 Map（调用方退化为按文件名解析）。
     *
     * 标签编码修复：老文件（WAV/ID3v2.3）常以 GBK/Big5 写标签，ffprobe 原样输出
     * 非 UTF-8 字节；若在 Java 侧直接按 UTF-8 解码会产生 U+FFFD 且不可逆。
     * 因此保留原始字节：先按 UTF-8 解析；若标签含 U+FFFD，再用 GBK/Big5 等
     * 严格解码整份输出重新解析，逐键替换为无乱码版本（兼容同一文件内
     * UTF-8 歌词 + GBK 标题的混合编码）。
     */
    public Map<String, Object> probeAudioInfo(String inputPath) {
        try {
            String[] cmd = {ffprobePath, "-v", "error",
                    "-print_format", "json", "-show_format", "-show_streams", inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();

            // 先等退出再读输出：进程挂起时 waitFor 超时后强杀，流随之 EOF，避免永久阻塞
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return Map.of();
            }

            byte[] rawOutput;
            try (var is = p.getInputStream()) {
                rawOutput = is.readAllBytes();
            }
            if (p.exitValue() != 0 || rawOutput.length == 0) {
                return Map.of();
            }

            // 主解析：UTF-8 宽松解码（与历史行为一致）
            String output = new String(rawOutput, StandardCharsets.UTF_8).trim();
            Map<String, Object> result = parseProbeOutput(output);

            // 标签出现 U+FFFD → 尝试其他字符集严格解码后逐键修复
            if (tagsContainReplacementChar(result)) {
                for (String charsetName : List.of("GBK", "Big5", "SHIFT_JIS", "EUC-KR")) {
                    String altOutput = strictDecode(rawOutput, charsetName);
                    if (altOutput == null) continue;
                    Map<String, Object> altResult;
                    try {
                        altResult = parseProbeOutput(altOutput.trim());
                    } catch (Exception ignored) {
                        continue;
                    }
                    mergeCleanerTags(result, altResult);
                    if (!tagsContainReplacementChar(result)) break;
                }
                log.info("[Probe] Repaired mojibake tags via alternate charset: input={}", inputPath);
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to probe audio {}: {}", inputPath, e.getMessage());
            return Map.of();
        }
    }

    /** 将 ffprobe JSON 输出解析为探测结果 Map（结构提取，与字符集无关）。 */
    private Map<String, Object> parseProbeOutput(String output) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(output);
        var result = new java.util.LinkedHashMap<String, Object>();

        var formatNode = root.path("format");
        if (formatNode.isObject()) {
            if (formatNode.hasNonNull("duration")) {
                result.put("duration", formatNode.path("duration").asDouble());
            }
            if (formatNode.hasNonNull("bit_rate")) {
                result.put("bitrate", formatNode.path("bit_rate").asLong());
            }
            if (formatNode.hasNonNull("format_name")) {
                result.put("format", formatNode.path("format_name").asText());
            }
            if (formatNode.hasNonNull("tags") && formatNode.path("tags").isObject()) {
                mergeTags(result, formatNode);
            }
        }

        var streams = root.path("streams");
        if (streams.isArray()) {
            for (var stream : streams) {
                if ("audio".equals(stream.path("codec_type").asText())) {
                    if (stream.hasNonNull("tags") && stream.path("tags").isObject()) {
                        mergeTags(result, stream);
                    }
                    if (stream.hasNonNull("codec_name")) {
                        result.put("codec", stream.path("codec_name").asText());
                    }
                    if (stream.hasNonNull("sample_rate")) {
                        result.put("sampleRate", stream.path("sample_rate").asInt());
                    }
                    if (stream.hasNonNull("bit_rate")) {
                        result.put("bitrate", stream.path("bit_rate").asLong());
                    }
                    break;
                }
            }
            for (var stream : streams) {
                if (stream.path("disposition").path("attached_pic").asInt(0) == 1) {
                    result.put("attachedPicture", true);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean tagsContainReplacementChar(Map<String, Object> result) {
        Object tagsObj = result.get("tags");
        if (!(tagsObj instanceof Map<?, ?> tags)) return false;
        return tags.values().stream()
                .anyMatch(v -> v instanceof String s && s.contains("\uFFFD"));
    }

    /**
     * 用备用字符集严格解码原始输出；任何非法字节都返回 null（保证不是凑巧解对）。
     */
    private static String strictDecode(byte[] raw, String charsetName) {
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.Charset.forName(charsetName)
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 逐键合并更干净的标签值：仅当主结果某键含 U+FFFD 而候选结果同键不含时替换，
     * 兼容「UTF-8 歌词 + GBK 标题」混编文件——不会用 GBK 误读破坏原本正确的 UTF-8 值。
     */
    @SuppressWarnings("unchecked")
    private void mergeCleanerTags(Map<String, Object> primary, Map<String, Object> candidate) {
        Object pTagsObj = primary.get("tags");
        Object cTagsObj = candidate.get("tags");
        if (!(pTagsObj instanceof Map<?, ?>) || !(cTagsObj instanceof Map<?, ?>)) return;
        var pTags = (Map<Object, Object>) pTagsObj;
        var cTags = (Map<Object, Object>) cTagsObj;
        for (var entry : cTags.entrySet()) {
            Object cur = pTags.get(entry.getKey());
            Object alt = entry.getValue();
            boolean curBad = cur instanceof String s && s.contains("\uFFFD");
            boolean altOk = alt instanceof String s && !s.contains("\uFFFD") && !s.isBlank();
            if (curBad && altOk) {
                pTags.put(entry.getKey(), alt);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeTags(Map<String, Object> result, com.fasterxml.jackson.databind.JsonNode node) {
        var tags = (Map<String, String>) result.computeIfAbsent(
                "tags", ignored -> new java.util.LinkedHashMap<String, String>());
        node.path("tags").fields().forEachRemaining(entry ->
                tags.put(entry.getKey(), fixMojibake(entry.getValue().asText(""))));
    }

    /**
     * 修复老音乐文件常见的标签乱码：ID3v2.3 时代中文常以 GBK/Big5 字节写入，
     * ffprobe 按 UTF-8 解读后产生 U+FFFD（�）或 CJK 兼容区错字。
     * 策略：
     * 1) 含 U+FFFD 时，把字符串按 UTF-8 编码回字节，再依次尝试 GBK/Big5 解码，
     *    取能无 � 完整解码的第一个；
     * 2) 全部失败则返回原串。
     */
    private static String fixMojibake(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (!raw.contains("\uFFFD")) return raw;

        byte[] bytes;
        try {
            bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
        String[] candidates = {"GBK", "Big5", "SHIFT_JIS", "EUC-KR"};
        for (String charset : candidates) {
            try {
                java.nio.charset.Charset cs = java.nio.charset.Charset.forName(charset);
                // REPLACE 行为会引入新 �，用 report 逐个验证
                java.nio.charset.CharsetDecoder decoder = cs.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                String decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (!decoded.contains("\uFFFD")) {
                    return decoded;
                }
            } catch (Exception ignored) {
                // 该字符集解不出，尝试下一个
            }
        }
        return raw;
    }


    /**
     * 转码后的视频流
     */
    public TranscodeResult transcode(String inputPath, String quality, String maxBitrate, String subtitlePath) throws IOException {
        int width = getWidthForQuality(quality);
        String requested = sanitizeBitrate(maxBitrate);
        String bitrate = requested != null ? requested : getDefaultBitrate(quality);
        String bufsize = parseBitrate(bitrate) * 2 + "k";

        // 先探测时长
        double duration = probeDuration(inputPath);

        List<String> command = buildTranscodeCommand(inputPath, width, bitrate, bufsize, duration, subtitlePath);
        log.debug("Transcoding {} -> {} @ {} (bufsize={}, duration={}s, subtitle={})", inputPath, quality, bitrate, bufsize, duration, subtitlePath != null ? "yes" : "no");
        log.debug("FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (libraryDir != null) {
            pb.environment().put(getLibraryPathEnv(), libraryDir);
        }
        pb.redirectErrorStream(false);
        Process process;

        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("Failed to start FFmpeg: {}", e.getMessage());
            throw e;
        }

        log.debug("FFmpeg process started, pid={}", process.pid());

        // 后台线程处理 stderr（warning 级别逐行打日志会刷屏，降为 debug）
        Thread.startVirtualThread(() -> {
            try (var err = process.getErrorStream();
                 var reader = new BufferedReader(new InputStreamReader(err))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("FFmpeg stderr: {}", line);
                }
            } catch (IOException ignored) {}
        });

        return new TranscodeResult(process, process.getInputStream());
    }

    /**
     * 公开探测视频时长（秒），供外部计算采样位置
     */
    public double getDurationSeconds(String inputPath) {
        return probeDuration(inputPath);
    }

    /**
     * 使用 ffprobe 探测视频时长
     */
    private double probeDuration(String inputPath) {
        try {
            String[] cmd = {ffprobePath, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();

            // 先等退出再读输出：避免进程挂起时 readAllBytes 永久阻塞、超时保护失效
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return 0;
            }

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            if (p.exitValue() == 0 && !output.isEmpty()) {
                return Double.parseDouble(output);
            }
        } catch (Exception e) {
            log.debug("Failed to probe duration: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 使用 ffprobe 探测视频分辨率，返回如 "3840x2160"；失败返回 null。
     * 供扫描时填充 Video.resolution。
     */
    public String probeResolution(String inputPath) {
        try {
            String[] cmd = {ffprobePath, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();

            // 先等退出再读输出：避免进程挂起时 readAllBytes 永久阻塞、超时保护失效
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            if (p.exitValue() == 0 && !output.isEmpty()) {
                // 输出形如 "1920x1080"（csv=s=x:p=0 只输出值）
                String[] parts = output.split("x");
                if (parts.length == 2) {
                    int width = Integer.parseInt(parts[0].trim());
                    int height = Integer.parseInt(parts[1].trim());
                    // 统一为 宽x高（横屏时 width>height；竖屏/旋转视频按较大值规范）
                    return Math.max(width, height) + "x" + Math.min(width, height);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to probe resolution: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从视频中截取一帧保存为 JPG（用于未刮削视频的封面）。
     * 取视频 15% 处的一帧（避开片头黑屏），失败时返回 false。
     * 默认竖屏尺寸 300x450。
     */
    public boolean extractFrame(String inputPath, String outputPath) {
        return extractFrame(inputPath, outputPath, 300, 450);
    }

    /**
     * 从视频中截取一帧保存为 JPG，可指定输出尺寸（竖屏海报或横屏背景图）。
     * 采样视频多个位置各截一帧，按内容复杂度（梯度能量）选出画面最丰富的帧，
     * 避免截到空镜/纯色画面。失败时返回 false。
     */
    public boolean extractFrame(String inputPath, String outputPath, int width, int height) {
        if (!ffmpegAvailable) {
            log.debug("FFmpeg not available, skip frame extraction");
            return false;
        }
        try {
            double duration = probeDuration(inputPath);
            // 在 12%~85% 区间均匀采样多个位置（避开片头片尾的标题/黑屏）
            double[] positions = {0.12, 0.28, 0.44, 0.60, 0.76, 0.85};

            List<Path> candidates = new ArrayList<>();
            Path tempDir = Files.createTempDirectory("fryfrog-frame");
            try {
                for (int i = 0; i < positions.length; i++) {
                    double pos = duration > 0 ? duration * positions[i] : 30 + i * 30;
                    Path tmp = tempDir.resolve("frame-" + i + ".jpg");
                    if (captureFrameAt(inputPath, tmp.toString(), width, height, pos)) {
                        candidates.add(tmp);
                    }
                }

                if (candidates.isEmpty()) {
                    log.debug("Frame extraction failed for all positions: {}", inputPath);
                    return false;
                }

                // 选出内容最丰富的一帧（梯度能量最高），复制到输出路径
                Path best = candidates.stream()
                        .max(Comparator.comparingDouble(this::contentScore))
                        .orElse(candidates.getFirst());
                Files.copy(best, Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
                log.debug("Extracted frame: {} -> {} ({} candidates)", inputPath, outputPath, candidates.size());
                return Files.exists(Paths.get(outputPath));
            } finally {
                deleteRecursively(tempDir);
            }
        } catch (Exception e) {
            log.warn("Failed to extract frame from {}: {}", inputPath, e.getMessage());
        }
        return false;
    }

    /** 在指定时间点截取一帧到目标文件（指定输出尺寸，crop 裁切） */
    public boolean captureFrameAt(String inputPath, String outputPath, int width, int height, double position) {
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-ss");
            command.add(String.valueOf(position));
            command.add("-i");
            command.add(inputPath);
            command.add("-frames:v");
            command.add("1");
            command.add("-vf");
            command.add("scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d"
                    .formatted(width, height, width, height));
            command.add("-q:v");
            command.add("2");
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 先等退出再读输出：避免进程挂起时 readAllBytes 永久阻塞、超时保护失效
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Frame extraction timed out: {}", inputPath);
                return false;
            }

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            if (p.exitValue() == 0 && Files.exists(Paths.get(outputPath))) {
                return true;
            }
            log.debug("Frame capture failed (exit={}) at {}: {} {}", p.exitValue(), position, inputPath, output);
        } catch (Exception e) {
            log.debug("Failed to capture frame at {}: {}", position, e.getMessage());
        }
        return false;
    }

    /**
     * 帧内容复杂度评分：缩略到 64x64 灰度图，计算相邻像素梯度能量之和。
     * 空镜/纯色画面梯度低，人物/场景轮廓和纹理多则梯度高。
     */
    private double contentScore(Path imagePath) {
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img == null) return 0;

            int size = 64;
            int[][] gray = new int[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int rgb = img.getRGB(x * img.getWidth() / size, y * img.getHeight() / size);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    gray[y][x] = (r * 299 + g * 587 + b * 114) / 1000;
                }
            }

            double energy = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (x > 0) energy += Math.abs(gray[y][x] - gray[y][x - 1]);
                    if (y > 0) energy += Math.abs(gray[y][x] - gray[y - 1][x]);
                }
            }
            return energy;
        } catch (Exception e) {
            log.debug("Failed to score frame {}: {}", imagePath, e.getMessage());
            return 0;
        }
    }

    private void deleteRecursively(java.nio.file.Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private int parseBitrate(String bitrate) {
        String lower = bitrate.toLowerCase();
        if (lower.endsWith("m")) {
            return (int) (Double.parseDouble(lower.replace("m", "")) * 1000);
        } else if (lower.endsWith("k")) {
            return (int) Double.parseDouble(lower.replace("k", ""));
        }
        return 8000;
    }

    /**
     * maxBitrate 白名单校验：仅允许 "8M"、"1.5M"、"500k" 这类格式；
     * 非法值返回 null（回退到该画质默认码率），避免脏值直接进入 ffmpeg 参数。
     */
    private String sanitizeBitrate(String maxBitrate) {
        if (maxBitrate == null || maxBitrate.isBlank()) return null;
        return maxBitrate.toLowerCase().matches("\\d+(\\.\\d+)?[mk]") ? maxBitrate.toLowerCase() : null;
    }

    private List<String> buildTranscodeCommand(String inputPath, int width, String bitrate, String bufsize, double duration, String subtitlePath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");

        // 如果探测到时长，限制输出时长
        if (duration > 0) {
            cmd.add("-t");
            cmd.add(String.valueOf((long) duration));
        }

        cmd.add("-i");
        cmd.add(inputPath);

        // 视频滤镜：缩放 + 可选字幕烧录，-2 保持宽高比
        String filter = "scale=" + width + ":-2";
        if (subtitlePath != null && !subtitlePath.isBlank()) {
            String filterName = isBitmapSubtitle(subtitlePath) ? "subtitle" : "subtitles";
            filter += "," + filterName + "=" + escapeFilterPath(subtitlePath);
        }
        cmd.add("-vf");
        cmd.add(filter);

        // 视频编码（libx264 几乎在所有 FFmpeg 发行版中可用）
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-b:v");
        cmd.add(bitrate);
        cmd.add("-maxrate");
        cmd.add(bitrate);
        cmd.add("-bufsize");
        cmd.add(bufsize);

        // 音频编码
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-ar");
        cmd.add("48000");

        // 输出格式：分片 MP4，支持流式播放
        cmd.add("-movflags");
        cmd.add("frag_keyframe+empty_moov");
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add("pipe:1");

        return cmd;
    }

    /**
     * 滤镜参数中的路径需要转义特殊字符（Windows 盘符冒号、单引号、
     * 滤镜参数分隔符逗号、滤镜链分隔符分号、标签方括号等）
     */
    private String escapeFilterPath(String path) {
        return path.replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    /**
     * 图形字幕（PGS/VobSub）需用 subtitle 滤镜，文本字幕（SRT/ASS/VTT）用 subtitles 滤镜
     */
    private boolean isBitmapSubtitle(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".sup") || lower.endsWith(".sub") || lower.endsWith(".idx");
    }

    private int getWidthForQuality(String quality) {
        return switch (quality.toLowerCase()) {
            case "1080p", "1080" -> 1920;
            case "720p", "720" -> 1280;
            case "480p", "480" -> 854;
            case "360p", "360" -> 640;
            default -> 1920;
        };
    }

    private String getDefaultBitrate(String quality) {
        return switch (quality.toLowerCase()) {
            case "1080p", "1080" -> "8M";
            case "720p", "720" -> "5M";
            case "480p", "480" -> "2M";
            case "360p", "360" -> "1M";
            default -> "8M";
        };
    }

    public record TranscodeResult(Process process, InputStream inputStream) implements Closeable {
        @Override
        public void close() throws IOException {
            inputStream.close();
            if (process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
