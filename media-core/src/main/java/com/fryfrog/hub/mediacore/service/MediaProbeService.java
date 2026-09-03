package com.fryfrog.hub.mediacore.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 媒体探测服务：封装 ffprobe 的音频信息、视频时长、视频分辨率探测。
 * 音频标签乱码修复逻辑（GBK/Big5 等字符集）集中在此。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaProbeService {

    private final FFmpegRuntime ffmpegRuntime;

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
            String[] cmd = {ffmpegRuntime.ffprobePath(), "-v", "error",
                    "-print_format", "json", "-show_format", "-show_streams", inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            ffmpegRuntime.applyLibraryEnv(pb);
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
            bytes = raw.getBytes(StandardCharsets.UTF_8);
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
     * 使用 ffprobe 探测音频文件的内嵌章节（M4B/M4A 有声书）。
     * 返回列表按出现顺序，每项键：start(秒)、end(秒)、title。
     * 失败或无章节返回空列表。
     */
    public List<Map<String, Object>> probeChapters(String inputPath) {
        try {
            String[] cmd = {ffmpegRuntime.ffprobePath(), "-v", "error",
                    "-print_format", "json", "-show_chapters", inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            ffmpegRuntime.applyLibraryEnv(pb);
            Process p = pb.redirectErrorStream(true).start();

            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return List.of();
            }

            String output;
            try (var is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (p.exitValue() != 0 || output.isEmpty()) {
                return List.of();
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(output);
            var chapters = root.path("chapters");
            if (!chapters.isArray() || chapters.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (var ch : chapters) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("start", ch.path("start_time").asDouble(0));
                item.put("end", ch.path("end_time").asDouble(0));
                item.put("title", fixMojibake(ch.path("tags").path("title").asText("")));
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to probe chapters {}: {}", inputPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * 使用 ffprobe 探测视频时长（秒），失败返回 0。
     */
    public double getDurationSeconds(String inputPath) {
        try {
            String[] cmd = {ffmpegRuntime.ffprobePath(), "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            ffmpegRuntime.applyLibraryEnv(pb);
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
            String[] cmd = {ffmpegRuntime.ffprobePath(), "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    inputPath};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            ffmpegRuntime.applyLibraryEnv(pb);
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
}
