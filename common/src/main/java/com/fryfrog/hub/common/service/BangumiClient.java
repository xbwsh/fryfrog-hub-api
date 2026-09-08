package com.fryfrog.hub.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Bangumi（bgm.tv）公开 API 客户端，供 audiobook / ebook 等模块共用。
 * 匿名即可访问，但站点强制要求规范 User-Agent；走 scraperRestTemplate（支持代理/SSL 放宽）。
 * 条目类型：1=书籍 2=动画 3=音乐 4=游戏 6=三次元（有声书/广播剧）。
 */
@Service
@Slf4j
public class BangumiClient {

    public static final String USER_AGENT = "fryfrog-hub/1.0 (https://github.com/xiamu/fryfrog-hub-api)";
    public static final int TYPE_BOOK = 1;
    public static final int TYPE_REAL = 6;

    private static final String SEARCH_PATH = "/v0/search/subjects?limit=20";
    private static final String SUBJECT_PATH = "/v0/subjects/";

    private final RestTemplate restTemplate;

    @Getter
    private final String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    public BangumiClient(@Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate,
                         @Value("${bangumi.base-url:https://api.bgm.tv}") String baseUrl) {
        this.restTemplate = scraperRestTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 条目搜索，返回 data 数组（可能为空数组）。 */
    public JsonNode searchSubjects(String keyword, List<Integer> types) throws Exception {
        Map<String, Object> body = types == null || types.isEmpty()
                ? Map.of("keyword", keyword)
                : Map.of("keyword", keyword, "filter", Map.of("type", types));
        HttpHeaders headers = jsonHeaders();
        String resp = restTemplate.postForObject(
                baseUrl + SEARCH_PATH, new HttpEntity<>(body, headers), String.class);
        if (resp == null || resp.isBlank()) return mapper.createArrayNode();
        return mapper.readTree(resp).path("data");
    }

    /** 条目详情；不存在或无响应返回 null。 */
    public JsonNode getSubject(String sourceId) throws Exception {
        if (sourceId == null || !sourceId.matches("\\d+")) return null;
        String resp = restTemplate.getForObject(baseUrl + SUBJECT_PATH + sourceId, String.class);
        if (resp == null || resp.isBlank()) return null;
        return mapper.readTree(resp);
    }

    /**
     * infobox 形如 [{"key":"作者","value":"刘慈欣"}, {"key":"链接","value":[{"k":"..","v":".."}]}]。
     * value 兼容裸字符串与数组（数组元素取 v 字段，以「、」连接）；keys 依序取首个命中。
     */
    public static String infoboxValue(JsonNode subject, String... keys) {
        for (String key : keys) {
            for (JsonNode item : subject.path("infobox")) {
                if (!key.equals(item.path("key").asText())) continue;
                String text = infoboxText(item.path("value"));
                if (text != null) return text;
            }
        }
        return null;
    }

    private static String infoboxText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isTextual()) return blankToNull(value.asText());
        if (value.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode element : value) {
                String part = element.isTextual() ? element.asText() : element.path("v").asText();
                if (part == null || part.isBlank()) continue;
                if (!sb.isEmpty()) sb.append("、");
                sb.append(part.strip());
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return blankToNull(value.asText(null));
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return headers;
    }
}
