package com.fryfrog.hub.common.util;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

/**
 * 繁体中文转简体中文（基于 opencc4j，完整覆盖）
 */
public final class ChineseConverter {

    private ChineseConverter() {}

    /** 繁体转简体 */
    public static String toSimplified(String text) {
        if (text == null || text.isBlank()) return text;
        return ZhConverterUtil.convertToSimple(text);
    }
}
