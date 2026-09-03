package com.fryfrog.hub.video.service;

import com.fryfrog.hub.mediacore.service.FFmpegRuntime;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频截帧服务：从视频中截取关键帧（用于未刮削视频的封面/背景图），
 * 以及候选帧生成/内容评分。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FrameCaptureService {

    private final FFmpegRuntime ffmpegRuntime;
    private final MediaProbeService probeService;

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
        if (!ffmpegRuntime.isAvailable()) {
            log.debug("FFmpeg not available, skip frame extraction");
            return false;
        }
        try {
            double duration = probeService.getDurationSeconds(inputPath);
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
            command.add(ffmpegRuntime.ffmpegPath());
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
            ffmpegRuntime.applyLibraryEnv(pb);
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

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
