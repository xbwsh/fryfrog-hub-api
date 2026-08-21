package com.fryfrog.hub.music.service;

import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音频标签直读服务：用 jaudiotagger 直接解析文件内标签（ID3/Vorbis/MP4/WAV LIST），
 * 绕过 ffprobe 输出层的 UTF-8 净化（其会把非法字节替换为 U+FFFD，导致中文老标签不可逆乱码）。
 *
 * 修复策略：jaudiotagger 对 Latin-1 声明的 ID3 帧按 ISO-8859-1 解码（字节↔字符无损），
 * 若结果疑似「GBK 字节被误标为 Latin-1」（常见于国内老 ripping 工具），把字符串按
 * ISO-8859-1 还原字节后尝试 GBK/Big5 解码，取含 CJK 的有效结果。
 */
@Service
@Slf4j
public class MusicTagReaderService {

    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
    private static final String[] RETRY_CHARSETS = {"GBK", "Big5"};

    /**
     * 读取标签，返回键：title/artist/album/albumArtist/track/disc/year/genre/lyrics。
     * 值均已做乱码修复；文件无标签或解析失败返回空 Map（调用方回退 ffprobe/文件名）。
     */
    public Map<String, String> readTags(File file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            AudioFile audio = AudioFileIO.read(file);
            Tag tag = audio.getTag();
            if (tag == null || tag.isEmpty()) return out;

            put(out, "title", repair(tag.getFirst(FieldKey.TITLE)));
            put(out, "artist", repair(tag.getFirst(FieldKey.ARTIST)));
            put(out, "album", repair(tag.getFirst(FieldKey.ALBUM)));
            put(out, "albumArtist", repair(tag.getFirst(FieldKey.ALBUM_ARTIST)));
            put(out, "track", repair(tag.getFirst(FieldKey.TRACK)));
            put(out, "disc", repair(tag.getFirst(FieldKey.DISC_NO)));
            put(out, "year", repair(tag.getFirst(FieldKey.YEAR)));
            put(out, "genre", repair(tag.getFirst(FieldKey.GENRE)));
            try {
                put(out, "lyrics", repair(tag.getFirst(FieldKey.LYRICS)));
            } catch (Exception ignored) {
                // 部分格式不支持 LYRICS 字段
            }
        } catch (Exception e) {
            log.debug("[TagReader] Failed to read tags: file={}, error={}", file.getName(), e.getMessage());
        }
        return out;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value.trim());
    }

    /**
     * 单值乱码修复：
     * 1) 含 U+FFFD 或整体为高位 Latin-1 字符（0x80-0xFF 占比高）→ 疑似误标编码；
     * 2) 按 ISO-8859-1 还原原始字节，依次尝试 GBK/Big5 严格解码；
     * 3) 取第一个「无替换符且含 CJK 或可读 ASCII」的结果；全部失败返回原值。
     */
    static String repair(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (!needsRepair(raw)) return raw;

        byte[] bytes;
        try {
            bytes = raw.getBytes(LATIN1);
        } catch (Exception e) {
            return raw;
        }
        for (String charsetName : RETRY_CHARSETS) {
            try {
                CharsetDecoderHolder decoder = new CharsetDecoderHolder(charsetName);
                String decoded = decoder.decode(bytes);
                if (decoded != null && looksValid(decoded)) {
                    log.info("[TagReader] Repaired mojibake via {}: {} -> {}", charsetName, abbreviate(raw), abbreviate(decoded));
                    return decoded;
                }
            } catch (Exception ignored) {
                // 尝试下一字符集
            }
        }
        return raw;
    }

    /** 疑似乱码：含 U+FFFD，或高位 Latin-1 字符占比超过 30%（正常西文不会这样）。 */
    private static boolean needsRepair(String s) {
        if (s.contains("\uFFFD")) return true;
        int high = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80 && c <= 0xFF) high++;
        }
        return s.length() > 0 && high * 10 >= s.length() * 3;
    }

    /** 修复结果有效性：不含替换符，且含 CJK 或以可读字符为主。 */
    private static boolean looksValid(String s) {
        if (s.contains("\uFFFD")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
                return true; // 含 CJK 即认为解对了
            }
        }
        // 无 CJK：若原值就是纯符号/数字场景，保持原样更安全
        return false;
    }

    private static String abbreviate(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    /** 简单封装 CharsetDecoder REPORT 模式。 */
    private static final class CharsetDecoderHolder {
        private final java.nio.charset.CharsetDecoder decoder;

        CharsetDecoderHolder(String charsetName) {
            this.decoder = Charset.forName(charsetName).newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        }

        String decode(byte[] bytes) throws Exception {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
}
