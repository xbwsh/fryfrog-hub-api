package com.fryfrog.hub.video.service;

import com.fryfrog.hub.mediacore.service.FFmpegRuntime;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频转码服务：实时转码为流式 MP4。
 * FFmpeg 路径管理见 {@link FFmpegRuntime}，探测与截帧见 {@link MediaProbeService}/{@link FrameCaptureService}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscodingService {

    private final FFmpegRuntime ffmpegRuntime;
    private final MediaProbeService probeService;

    public boolean isAvailable() {
        return ffmpegRuntime.isAvailable();
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
        double duration = probeService.getDurationSeconds(inputPath);

        List<String> command = buildTranscodeCommand(inputPath, width, bitrate, bufsize, duration, subtitlePath);
        log.debug("Transcoding {} -> {} @ {} (bufsize={}, duration={}s, subtitle={})", inputPath, quality, bitrate, bufsize, duration, subtitlePath != null ? "yes" : "no");
        log.debug("FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        ffmpegRuntime.applyLibraryEnv(pb);
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
        cmd.add(ffmpegRuntime.ffmpegPath());
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
