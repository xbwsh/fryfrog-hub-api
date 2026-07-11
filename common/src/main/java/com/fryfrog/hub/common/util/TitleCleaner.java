package com.fryfrog.hub.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的标题清洗工具类。漫画、电子书、视频共用。
 * <p>
 * 核心方法：
 * <ul>
 *   <li>{@link #cleanForSearch(String)} — 清洗标题用于搜索/刮削（去除标签、质量标记、集数等）</li>
 *   <li>{@link #extractVolumeNumber(String)} — 从文件名提取卷号/集数</li>
 *   <li>{@link #extractSeriesNameFromFileName(String)} — 从文件名提取系列名</li>
 *   <li>{@link #sanitizeFileName(String)} — 文件系统安全的文件名</li>
 *   <li>{@link #sanitizeFolderName(String)} — 文件系统安全的文件夹名</li>
 *   <li>{@link #calculateSimilarity(String, String)} — 字符串相似度计算</li>
 * </ul>
 */
public final class TitleCleaner {

    private TitleCleaner() {}

    // ═══════════════════════════════════════════════════════════
    //  常量：CJK Unicode 范围
    // ═══════════════════════════════════════════════════════════

    /** CJK 统一汉字 + 扩展A + 平假名 + 片假名 */
    private static final String CJK_RANGE =
            "\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff";

    private static final Pattern CJK_CHAR_PATTERN = Pattern.compile("[" + CJK_RANGE + "]");

    /** 捕获组版本，用于替换尾部 CJK+数字 */
    private static final Pattern CJK_TAIL_NUMBER =
            Pattern.compile("([" + CJK_RANGE + "])(\\d{1,3})$");

    // ═══════════════════════════════════════════════════════════
    //  常量：文件扩展名
    // ═══════════════════════════════════════════════════════════

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile(
            "(?i)\\.(?:cbz|cbr|zip|rar|epub|pdf|mobi|azw|azw3|fb2|txt|mp4|mkv|avi|mov|flv|wmv|webm|m4v|ts)$"
    );

    // ═══════════════════════════════════════════════════════════
    //  常量：视频质量/编码/来源标记
    // ═══════════════════════════════════════════════════════════

    private static final String DOTTED_QUALITY =
            "(?i)\\bH\\.?264\\b|\\bH\\.?265\\b|\\bDD[P+]\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b" +
            "|\\bDTS(?:\\s*-?\\s*HD)?(?:\\s*\\.?\\s*(?:MA|ES|RA))?(?:\\s*\\.?\\s*\\d+(?:\\.\\d+)*)?\\b" +
            "|\\bAC\\s*\\.?\\s*3\\b|\\bE\\s*-?\\s*AC\\s*-?\\s*3\\b" +
            "|\\bA(?:V|C)\\s*\\.?\\s*A(?:V|C)?\\s*\\.?\\s*1\\b" +
            "|\\bMPEG\\s*-?\\s*[24]\\b" +
            "|\\bFLAC\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b" +
            "|\\b[2-7]\\.\\d\\b";

    private static final String QUALITY_PATTERN =
            "(?i)\\b(?:2160p|1080p|720p|480p|4K|UHD|FHD)\\b" +
            "|\\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|TVRip|CamRip|TS|TC|SCR|R5)\\b" +
            "|\\b(?:x264|x265|HEVC|AVC|AV1)\\b" +
            "|\\b(?:AAC|FLAC|DTS(?:MA|HD)?|AC3|EAC3|DDP?\\d|Atmos|TrueHD|MP3|OGG|Opus)\\b" +
            "|\\bHDR(?:10?)?\\b|\\bDoVi\\b|\\bDV\\b|\\bHLG\\b" +
            "|\\bDolby\\s*Vision\\b|\\bDolby\\b" +
            "|\\b(?:10-?bit|8-?bit)\\b" +
            "|\\bREMUX\\b|\\bBlu-?ray\\b" +
            "|\\b(?:AVS|FRDS|Ma10p|Ma10s|NCOP|NCED)\\b" +
            "|\\b(?:Baha|ADBA|Bilibili|ABEMA|Crunchyroll|Funimation|Netflix|NF|Disney\\+?|Amazon|Hulu|Hi10|Nazzy|FGT|SPARKS|SHAFT)\\b" +
            "|\\b(?:ASS|SSA|SRT|BIG5|GB2312|UTF-?8|EUC-?JP|Shift-?JIS)\\b" +
            "|\\b(?:PART|CHAPTER|CD|DVD|BD|DISC?)\\b" +
            "|\\b(?:19|20)\\d{2}\\b|\\d*Audio\\b" +
            "|\\b(?:mp4|mkv|avi|mov|flv|wmv|webm|m4v|ts)\\b";

    private static final Pattern DOTTED_QUALITY_PATTERN = Pattern.compile(DOTTED_QUALITY);
    private static final Pattern QUALITY_COMPILE_PATTERN = Pattern.compile(QUALITY_PATTERN);

    // ═══════════════════════════════════════════════════════════
    //  常量：卷号/集数提取正则（按优先级排序）
    // ═══════════════════════════════════════════════════════════

    /** 第X卷/章/集/话/期/部/季 — 中文数字或阿拉伯数字 */
    private static final Pattern VOLUME_CHINESE = Pattern.compile(
            "第\\s*([一二三四五六七八九十百千万零\\d]+)\\s*(?:卷|章|集|话|期|部|季)"
    );

    /** Vol.X / Volume X / VOL.X */
    private static final Pattern VOLUME_VOL = Pattern.compile(
            "(?i)(?:Vol\\.?|Volume)\\s*(\\d+)"
    );

    /** EP.X / E X / Episode X */
    private static final Pattern VOLUME_EP = Pattern.compile(
            "(?i)EP?\\s*(\\d{1,4})"
    );

    /** #X / ＃X */
    private static final Pattern VOLUME_HASH = Pattern.compile(
            "[＃#]\\s*(\\d+)"
    );

    /** 卷X（无"第"字前缀） */
    private static final Pattern VOLUME_JUAN = Pattern.compile(
            "卷\\s*(\\d+)"
    );

    /** [X] / (X) / （X） — 方括号/圆括号中的纯数字 */
    private static final Pattern VOLUME_BRACKET = Pattern.compile(
            "[\\[（(]\\s*(?:第?\\s*)?\\d+\\s*(?:卷|集|话|期)?\\s*[\\]）)]"
    );

    /** [X] / (X) 中的纯数字捕获 */
    private static final Pattern VOLUME_BRACKET_NUMBER = Pattern.compile(
            "[\\[（(]\\s*(\\d+)\\s*[\\]）)]"
    );

    /** 空格/点/下划线/全角空格 + 数字 在末尾 */
    private static final Pattern VOLUME_SPACE_TAIL = Pattern.compile(
            "[\\s._\\-　](\\d{1,3})$"
    );

    /** CJK 字符直接连接数字在末尾：魔都精兵的奴隶1 */
    private static final Pattern VOLUME_CJK_TAIL = Pattern.compile(
            "([" + CJK_RANGE + "])(\\d{1,3})$"
    );

    /** 破折号 + 数字 */
    private static final Pattern VOLUME_DASH = Pattern.compile(
            "[-–—]\\s*(\\d{1,4})\\b"
    );

    // ═══════════════════════════════════════════════════════════
    //  基础工具方法
    // ═══════════════════════════════════════════════════════════

    /** 文件系统安全的文件名（非法字符替换为下划线） */
    public static String sanitizeFileName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 文件系统安全的文件夹名（非法字符移除，压缩空格） */
    public static String sanitizeFolderName(String name) {
        if (name == null) return "Unknown";
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        return sanitized.isBlank() ? "Unknown" : sanitized;
    }

    /** 提取文件扩展名（不含点号） */
    public static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1) : "";
    }

    /** 检测字符串是否包含 CJK 字符 */
    public static boolean hasCJK(String text) {
        if (text == null || text.isBlank()) return false;
        return CJK_CHAR_PATTERN.matcher(text).find();
    }

    // ═══════════════════════════════════════════════════════════
    //  核心方法：标题清洗（用于搜索/刮削）
    // ═══════════════════════════════════════════════════════════

    /**
     * 清洗标题用于搜索。去除标签、质量标记、集数卷号等噪音。
     * 适用于漫画、电子书、视频所有媒体类型。
     */
    public static String cleanForSearch(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }

        // 繁体转简体
        String cleaned = ChineseConverter.toSimplified(title);

        // 1. 移除方括号/圆括号中的所有内容（标签、版本标记、源组等）
        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        // 2. 移除带点的编码格式（H.264, DTS-HD, AC.3 等）
        cleaned = DOTTED_QUALITY_PATTERN.matcher(cleaned).replaceAll(" ");

        // 3. 句点/下划线/全角句点替换为空格
        cleaned = cleaned.replaceAll("[._。．]", " ");

        // 4. 移除 S01E01 格式
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,4}\\b", " ");

        // 5. 移除视频质量/来源/编码/字幕/发布组标记
        cleaned = QUALITY_COMPILE_PATTERN.matcher(cleaned).replaceAll(" ");

        // 6. 移除中文季/部/期号：第一季、第二部、第三期
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:季|部|期)", " ");

        // 7. 移除中文集数标记：第一话、第二集、第三回、第四篇、第五章
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:话|集|回|篇|章)", " ");

        // 8. 移除英文季数标记：Season 1, S01
        cleaned = cleaned.replaceAll("(?i)\\bSeason\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\b", " ");

        // 9. 移除英文集数标记：Ep01, Episode 01, #1
        cleaned = cleaned.replaceAll("(?i)\\bE(?:p(?:isode)?)?\\s*\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("[＃#]\\s*\\d{1,4}\\b", " ");

        // 10. 移除卷号标记：Vol.1, vol 1, 卷1, 第1卷, #1
        cleaned = cleaned.replaceAll("(?i)\\bVol\\.?\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("卷\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("第\\s*\\d+\\s*卷", " ");

        // 11. 移除尾部：破折号+数字、空格+数字
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+\\d{1,4}\\s*$", "");

        // 12. 移除开头：数字+破折号
        cleaned = cleaned.replaceAll("^\\d{1,4}\\s*[-–—]\\s*", "");

        // 13. 清理尾部破折号和逗号
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*$", "");
        cleaned = cleaned.replaceAll("[,;]+\\s*$", "");

        // 14. 移除 CJK 字符直接连接的尾部数字（如 魔都精兵的奴隶1 → 魔都精兵的奴隶）
        cleaned = CJK_TAIL_NUMBER.matcher(cleaned).replaceAll("$1");

        // 15. 移除文件扩展名
        cleaned = FILE_EXTENSION_PATTERN.matcher(cleaned).replaceAll("");

        // 16. 统一破折号为空格
        cleaned = cleaned.replaceAll("\\s*[-–—]+\\s*", " ");

        // 17. 压缩多余空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    // ═══════════════════════════════════════════════════════════
    //  核心方法：卷号/集数提取
    // ═══════════════════════════════════════════════════════════

    /**
     * 从文件名提取卷号/集数。返回 null 表示未识别。
     * <p>
     * 支持的格式（按优先级）：
     * <ul>
     *   <li>第1卷 / 第01话 / 第一季 / 第二集</li>
     *   <li>Vol.1 / Volume 1 / VOL.01</li>
     *   <li>EP01 / E01 / Episode 1</li>
     *   <li>#1 / ＃01</li>
     *   <li>卷1</li>
     *   <li>[01] / (1)</li>
     *   <li>标题 01（空格分隔）</li>
     *   <li>标题01（CJK 直接连接）</li>
     *   <li>- 01（破折号后）</li>
     * </ul>
     */
    public static Integer extractVolumeNumber(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;

        // 第X卷/章/集/话/期/部/季
        Matcher m = VOLUME_CHINESE.matcher(fileName);
        if (m.find()) {
            return parseChineseOrDigit(m.group(1));
        }

        // Vol.X / Volume X
        m = VOLUME_VOL.matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // EP.X / E X
        m = VOLUME_EP.matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // #X / ＃X
        m = VOLUME_HASH.matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // 卷X（无"第"字）
        m = VOLUME_JUAN.matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // [X] / (X) 中的数字
        m = VOLUME_BRACKET_NUMBER.matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // 取文件基础名（去扩展名）用于尾部匹配
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        // 空格/点/下划线 + 数字 在末尾：标题 01
        m = VOLUME_SPACE_TAIL.matcher(baseName);
        if (m.find()) return Integer.parseInt(m.group(1));

        // CJK 字符直接连接数字在末尾：魔都精兵的奴隶1
        m = VOLUME_CJK_TAIL.matcher(baseName);
        if (m.find()) return Integer.parseInt(m.group(2));

        // 破折号 + 数字
        m = VOLUME_DASH.matcher(baseName);
        if (m.find()) return Integer.parseInt(m.group(1));

        return null;
    }

    /** 解析中文数字或阿拉伯数字 */
    private static int parseChineseOrDigit(String s) {
        if (s == null || s.isBlank()) return 0;
        // 尝试直接解析阿拉伯数字
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {}
        // 中文数字解析
        return chineseToNumber(s.trim());
    }

    /** 中文数字转阿拉伯数字（支持简单组合：一~十九、二十、三十、百、千） */
    static int chineseToNumber(String chinese) {
        if (chinese == null || chinese.isEmpty()) return 0;
        // 纯数字
        try {
            return Integer.parseInt(chinese);
        } catch (NumberFormatException ignored) {}

        int result = 0;
        int current = 0;
        for (int i = 0; i < chinese.length(); i++) {
            char c = chinese.charAt(i);
            int val = digitValue(c);
            if (val >= 10) {
                // 十/百/千 是单位
                if (current == 0) current = 1;
                if (val == 10000) {
                    result = (result + current) * 10000;
                    current = 0;
                } else if (val == 1000) {
                    result = (result + current) * 1000;
                    current = 0;
                } else if (val == 100) {
                    result = (result + current) * 100;
                    current = 0;
                } else if (val == 10) {
                    result = (result + current) * 10;
                    current = 0;
                }
            } else {
                current = val;
            }
        }
        return result + current;
    }

    private static int digitValue(char c) {
        return switch (c) {
            case '零' -> 0;
            case '一', '壹' -> 1;
            case '二', '贰', '两' -> 2;
            case '三', '叁' -> 3;
            case '四', '肆' -> 4;
            case '五', '伍' -> 5;
            case '六', '陆' -> 6;
            case '七', '柒' -> 7;
            case '八', '捌' -> 8;
            case '九', '玖' -> 9;
            case '十', '拾' -> 10;
            case '百', '佰' -> 100;
            case '千', '仟' -> 1000;
            case '万', '萬' -> 10000;
            default -> -1;
        };
    }

    // ═══════════════════════════════════════════════════════════
    //  核心方法：从文件名提取系列名
    // ═══════════════════════════════════════════════════════════

    /** 括号内容提取时的噪音关键词（纯标签/标记，非系列名） */
    private static final List<String> NOISE_KEYWORDS = List.of(
            "raw", "scanned", "digital", "uncensored", "censored",
            "hololive", "moe", "kmoe", "ahoge",
            "comic", "magazine", "tankoubon", "bilibili", "dmzj",
            "包子漫画", "拷贝漫画",
            "progressive", "extra", "online", "alzation", "unlasting",
            "rainbow", "clover", "regret", "mother", "deepening",
            "candid", "calibur", "divine", "editorial", "illustration",
            "fanbox", "doujin", "dl版", "tl", "翻",
            "台版", "日版", "港版", "简中", "繁中", "中文版",
            "dl", "tl", "hd"
    );

    /**
     * 从文件名提取系列名。
     * <p>
     * 策略：
     * <ol>
     *   <li>提取方括号/圆括号中的内容，过滤噪音关键词</li>
     *   <li>如果括号中有有效内容，优先返回</li>
     *   <li>回退到 cleanForSearch 清洗后的结果</li>
     *   <li>最终回退到 "Unknown"</li>
     * </ol>
     */
    public static String extractSeriesNameFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "Unknown";

        // 去扩展名
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        // 1. 尝试从括号内容提取
        List<String> bracketContents = extractBracketContents(baseName);
        String seriesFromBrackets = pickSeriesFromBrackets(bracketContents);
        if (seriesFromBrackets != null) {
            return seriesFromBrackets;
        }

        // 2. 回退到 cleanForSearch 清洗
        String cleaned = cleanForSearch(baseName);
        if (cleaned != null && !cleaned.isBlank()) {
            // 取第一段（按空白/点/下划线/破折号分割）
            String[] parts = cleaned.split("[\\s._\\-]+");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }

        // 3. 最终回退
        return "Unknown";
    }

    /** 从文件名中提取所有方括号/圆括号中的内容 */
    private static List<String> extractBracketContents(String text) {
        List<String> contents = new ArrayList<>();
        Matcher m = Pattern.compile("[\\[（(]([^\\]）)]+)[\\]）)]").matcher(text);
        while (m.find()) {
            contents.add(m.group(1).trim());
        }
        return contents;
    }

    /** 从括号内容列表中选择最佳系列名（过滤噪音） */
    private static String pickSeriesFromBrackets(List<String> contents) {
        String series = null;
        for (String content : contents) {
            // 跳过纯数字
            if (content.matches("\\d+")) continue;
            // 跳过噪音关键词
            if (isNoiseContent(content)) continue;
            // 跳过单字符（太短，可能是误匹配）
            if (content.length() > 1) {
                series = content;
            }
        }
        return series;
    }

    /** 检查括号内容是否为噪音（纯标签/标记） */
    private static boolean isNoiseContent(String content) {
        String lower = content.toLowerCase().trim();
        return NOISE_KEYWORDS.stream().anyMatch(kw -> kw.equalsIgnoreCase(lower));
    }

    // ═══════════════════════════════════════════════════════════
    //  核心方法：字符串相似度
    // ═══════════════════════════════════════════════════════════

    /**
     * 计算两个字符串的相似度（0.0 ~ 1.0）。
     * 预处理：转小写，移除所有非字母数字和 CJK 字符。
     */
    public static double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        String a = s1.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        String b = s2.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;
        int maxLen = Math.max(a.length(), b.length());
        int distance = levenshteinDistance(a, b);
        return 1.0 - (double) distance / maxLen;
    }

    /** 计算 Levenshtein 编辑距离 */
    public static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
