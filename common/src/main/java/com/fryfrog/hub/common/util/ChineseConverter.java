package com.fryfrog.hub.common.util;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

/**
 * 繁体中文 ↔ 简体中文转换工具（基于 opencc4j）
 */
public final class ChineseConverter {

    private ChineseConverter() {}

    /** 繁体转简体 */
    public static String toSimplified(String text) {
        if (text == null || text.isBlank()) return text;
        return ZhConverterUtil.toSimple(text);
    }

    /** 简体转繁体 */
    public static String toTraditional(String text) {
        if (text == null || text.isBlank()) return text;
        return ZhConverterUtil.toTraditional(text);
    }
}
