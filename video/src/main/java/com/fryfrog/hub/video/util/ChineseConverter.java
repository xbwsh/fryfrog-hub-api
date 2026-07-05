package com.fryfrog.hub.video.util;

import java.util.Map;

/**
 * 中文繁简转换工具
 * 使用常用词组替换进行繁体→简体转换
 */
public final class ChineseConverter {

    private ChineseConverter() {
    }

    /**
     * 繁体中文转简体中文
     */
    public static String toSimplified(String traditional) {
        if (traditional == null || traditional.isEmpty()) {
            return traditional;
        }
        String result = traditional;
        for (Map.Entry<String, String> entry : WORD_MAPPINGS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    // 常用繁体词组→简体词组
    private static final Map<String, String> WORD_MAPPINGS = Map.ofEntries(
            Map.entry("裏番", "里番"),
            Map.entry("最新上傳", "最新上传"),
            Map.entry("他們在看", "他们在看"),
            Map.entry("泡麵番", "泡面番"),
            Map.entry("類別", "类别"),
            Map.entry("標籤", "标签"),
            Map.entry("觀看次數", "观看次数"),
            Map.entry("相關影片", "相关影片"),
            Map.entry("系列影片", "系列影片"),
            Map.entry("影片描述", "影片描述"),
            Map.entry("發行日期", "发行日期"),
            Map.entry("製作商", "制作商"),
            Map.entry("發行商", "发行商"),
            Map.entry("搜尋", "搜索"),
            Map.entry("進階搜尋", "高级搜索"),
            Map.entry("排序方式", "排序方式"),
            Map.entry("最熱", "最热"),
            Map.entry("本日排行", "本日排行"),
            Map.entry("本月排行", "本月排行"),
            Map.entry("評分最高", "评分最高"),
            Map.entry("無碼", "无码"),
            Map.entry("有碼", "有码"),
            Map.entry("步兵", "步兵"),
            Map.entry("骑兵", "骑兵"),
            Map.entry("動畫", "动画"),
            Map.entry("國產", "国产"),
            Map.entry("歐美", "欧美"),
            Map.entry("日韓", "日韩"),
            Map.entry("寫真", "写真"),
            Map.entry("圖集", "图集"),
            Map.entry("影片", "影片"),
            Map.entry("視頻", "视频"),
            Map.entry("音樂", "音乐"),
            Map.entry("圖片", "图片"),
            Map.entry("小說", "小说"),
            Map.entry("漫畫", "漫画"),
            Map.entry("軟體", "软件"),
            Map.entry("網路", "网络"),
            Map.entry("電腦", "电脑"),
            Map.entry("手機", "手机"),
            Map.entry("號碼", "号码"),
            Map.entry("密碼", "密码"),
            Map.entry("註冊", "注册"),
            Map.entry("登入", "登录"),
            Map.entry("登出", "退出"),
            Map.entry("設定", "设置"),
            Map.entry("關於", "关于"),
            Map.entry("說明", "说明"),
            Map.entry("回應", "回复"),
            Map.entry("討論", "讨论"),
            Map.entry("評論", "评论"),
            Map.entry("推薦", "推荐"),
            Map.entry("下載", "下载"),
            Map.entry("上傳", "上传"),
            Map.entry("搜尋結果", "搜索结果"),
            Map.entry("沒有找到", "没有找到"),
            Map.entry("暫無", "暂无"),
            Map.entry("全部", "全部"),
            Map.entry("熱門", "热门"),
            Map.entry("最新", "最新")
    );
}
