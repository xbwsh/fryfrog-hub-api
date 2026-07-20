package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.util.FFmpegBinaryExtractor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TranscodingService {

    @Value("${video.ffmpeg-path:}")
    private String configuredFfmpegPath;

    private String ffmpegPath;
    private String ffprobePath;
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
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("FFmpeg check failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return ffmpegAvailable;
    }

    /**
     * 获取转码后的视频流
     */
    public TranscodeResult transcode(String inputPath, String quality, String maxBitrate) throws IOException {
        int width = getWidthForQuality(quality);
        String bitrate = maxBitrate != null ? maxBitrate : getDefaultBitrate(quality);
        String bufsize = parseBitrate(bitrate) * 2 + "k";

        // 先探测时长
        double duration = probeDuration(inputPath);

        List<String> command = buildTranscodeCommand(inputPath, width, bitrate, bufsize, duration);
        log.debug("Transcoding {} -> {} @ {} (bufsize={}, duration={}s)", inputPath, quality, bitrate, bufsize, duration);
        log.debug("FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
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

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

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

    private int parseBitrate(String bitrate) {
        String lower = bitrate.toLowerCase();
        if (lower.endsWith("m")) {
            return (int) (Double.parseDouble(lower.replace("m", "")) * 1000);
        } else if (lower.endsWith("k")) {
            return (int) Double.parseDouble(lower.replace("k", ""));
        }
        return 8000;
    }

    private List<String> buildTranscodeCommand(String inputPath, int width, String bitrate, String bufsize, double duration) {
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

        // 视频滤镜：缩放，-2 保持宽高比
        cmd.add("-vf");
        cmd.add("scale=" + width + ":-2");

        // 视频编码
        cmd.add("-c:v");
        cmd.add("libopenh264");
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
