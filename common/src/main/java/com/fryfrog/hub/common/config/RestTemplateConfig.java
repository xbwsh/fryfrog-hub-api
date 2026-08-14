package com.fryfrog.hub.common.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
public class RestTemplateConfig {

    @Value("${PROXY_HOST:}")
    private String proxyHost;

    @Value("${PROXY_PORT:0}")
    private int proxyPort;

    /** 刮削客户端是否信任所有证书。仅影响 scraper，不再全局污染 JVM。默认关闭。 */
    @Value("${scraper.bypass-ssl:false}")
    private boolean bypassSsl;

    @Bean
    public RestTemplate restTemplate() {
        return createSimpleRestTemplate(5000, 10000);
    }

    @Bean("scraperRestTemplate")
    public RestTemplate scraperRestTemplate() {
        if (!bypassSsl) {
            return createSimpleRestTemplate(10000, 30000);
        }
        return createBypassSslRestTemplate(10000, 30000);
    }

    private RestTemplate createSimpleRestTemplate(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        if (hasProxy()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        return new RestTemplate(factory);
    }

    /**
     * 仅对 scraper 客户端放宽 TLS 校验（自签名/内网 TMDB 镜像场景），
     * 通过 Apache HttpClient 的局部连接配置实现，不修改 JVM 全局默认 SSL。
     */
    private RestTemplate createBypassSslRestTemplate(int connectTimeout, int readTimeout) {
        try {
            TrustAllStrategy trustStrategy = new TrustAllStrategy();
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, trustStrategy)
                    .build();
            SSLConnectionSocketFactory sslSocketFactory =
                    new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslSocketFactory)
                    .build();
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofMilliseconds(connectTimeout))
                    .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofMilliseconds(readTimeout))
                    .build();
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .build();
            HttpComponentsClientHttpRequestFactory factory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create scraper HTTP client", e);
        }
    }

    private boolean hasProxy() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
    }
}