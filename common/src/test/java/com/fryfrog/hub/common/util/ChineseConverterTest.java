package com.fryfrog.hub.common.util;

public class ChineseConverterTest {
    public static void main(String[] args) throws Exception {
        String[] tests = {
            "刀劍神域",
            "魔都精兵的奴隸",
            "間諜過家家",
            "虛構推理",
            "進擊的巨人",
            "鐵拳教育",
            "電擊文庫",
            "川原礫",
            "輕小說",
            "漫畫",
            "這是一個測試",
            "簡體中文轉繁體中文",
            "龍與地下城",
            "魔法少女小圓",
            "新世紀福音戰士"
        };

        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.out.println("=== opencc4j \u7E41\u7B80\u8F6C\u6362\u6D4B\u8BD5 ===");
        for (String text : tests) {
            String result = ChineseConverter.toSimplified(text);
            System.out.printf("%-15s -> %s%n", text, result);
        }
    }
}
