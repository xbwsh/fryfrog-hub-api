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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
            String[] cmd = isWindows()
                    ? new String[]{"cmd", "/c", ffmpegPath, "-version"}
                    : new String[]{ffmpegPath, "-version"};
            ProcessBuilder pb = new ProcessBuilder(cmd);
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
     * 获取转码后的视频流
     */
    public TranscodeResult transcode(String inputPath, String quality, String maxBitrate, String subtitlePath) throws IOException {
        int width = getWidthForQuality(quality);
        String bitrate = maxBitrate != null ? maxBitrate : getDefaultBitrate(quality);
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

        // 后台线程处理 stderr
        Thread.startVirtualThread(() -> {
            try (var err = process.getErrorStream();
                 var reader = new BufferedReader(new InputStreamReader(err))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.warn("FFmpeg stderr: {}", line);
                }
            } catch (IOException ignored) {}
        });

        return new TranscodeResult(process, process.getInputStream());
    }

    /**
     * 使用 ffprobe 探测视频时长
     */
    private double probeDuration(String inputPath) {
        try {
            String[] cmd = isWindows()
                    ? new String[]{"cmd", "/c", ffprobePath, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputPath}
                    : new String[]{ffprobePath, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes()).trim();
            }

            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return 0;
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
                    if (captureFrame(inputPath, tmp.toString(), width, height, pos)) {
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

    /** 在指定时间点截取一帧到目标文件 */
    private boolean captureFrame(String inputPath, String outputPath, int width, int height, double position) {
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

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes()).trim();
            }

            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Frame extraction timed out: {}", inputPath);
                return false;
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
     * 滤镜参数中的路径需要转义特殊字符（Windows 盘符冒号、单引号等）
     */
    private String escapeFilterPath(String path) {
        return path.replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
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

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
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
