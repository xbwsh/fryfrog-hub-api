package com.fryfrog.hub.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChineseConverterTest {

    @Test
    void toSimplified_convertsCommonTraditionalCharacters() {
        // 这些字符在旧映射表中缺失，现在由 opencc4j 正确处理
        assertEquals("纯洁的轮舞曲", ChineseConverter.toSimplified("純潔的輪舞曲"));
        assertEquals("催眠性指导", ChineseConverter.toSimplified("催眠性指導"));
        assertEquals("刀剑神域", ChineseConverter.toSimplified("刀劍神域"));
        assertEquals("间谍过家家", ChineseConverter.toSimplified("間諜過家家"));
        assertEquals("进击的巨人", ChineseConverter.toSimplified("進擊的巨人"));
        assertEquals("魔法少女小圆", ChineseConverter.toSimplified("魔法少女小圓"));
        assertEquals("新世纪福音战士", ChineseConverter.toSimplified("新世紀福音戰士"));
    }

    @Test
    void toTraditional_convertsSimplifiedToTraditional() {
        assertEquals("魔法少女小圓", ChineseConverter.toTraditional("魔法少女小圆"));
        assertEquals("進擊的巨人", ChineseConverter.toTraditional("进击的巨人"));
    }

    @Test
    void handlesNullAndBlank() {
        assertNull(ChineseConverter.toSimplified(null));
        assertEquals("", ChineseConverter.toSimplified(""));
        assertEquals("  ", ChineseConverter.toSimplified("  "));
    }
}
