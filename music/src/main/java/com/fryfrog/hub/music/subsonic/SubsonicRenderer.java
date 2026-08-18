package com.fryfrog.hub.music.subsonic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.stereotype.Component;

/**
 * Subsonic 响应渲染：按 {@code f} 参数输出 JSON / XML / JSONP。
 * JSON 根节点 {@code subsonic-response} 由 {@code @JsonRootName} + WRAP_ROOT_VALUE 生成，
 * XML 根元素由 {@code @JacksonXmlRootElement} 生成。
 */
@Component
public class SubsonicRenderer {

    private final ObjectMapper jsonMapper;
    private final XmlMapper xmlMapper;

    public SubsonicRenderer() {
        this.jsonMapper = new ObjectMapper().configure(SerializationFeature.WRAP_ROOT_VALUE, true);
        this.xmlMapper = new XmlMapper();
    }

    public String render(SubsonicModel.Envelope envelope, String format, String callback) {
        try {
            if ("json".equalsIgnoreCase(format) || "jsonp".equalsIgnoreCase(format)) {
                String json = jsonMapper.writeValueAsString(envelope);
                if ("jsonp".equalsIgnoreCase(format)) {
                    String cb = callback == null || callback.isBlank() ? "callback" : callback;
                    return cb + "(" + json + ");";
                }
                return json;
            }
            return xmlMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Subsonic response", e);
        }
    }

    public static String contentType(String format) {
        if ("json".equalsIgnoreCase(format)) return "application/json; charset=utf-8";
        if ("jsonp".equalsIgnoreCase(format)) return "text/javascript; charset=utf-8";
        return "application/xml; charset=utf-8";
    }
}