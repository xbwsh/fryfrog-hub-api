package com.fryfrog.hub.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

@Configuration
public class RestTemplateConfig {

    @Value("${hub.proxy.host:}")
    private String proxyHost;

    @Value("${hub.proxy.port:0}")
    private int proxyPort;

    @Bean
    public RestTemplate restTemplate() {
        return createRestTemplate(5000, 10000, false);
    }

    @Bean("scraperRestTemplate")
    public RestTemplate scraperRestTemplate() {
        return createRestTemplate(10000, 30000, true);
    }

    private RestTemplate createRestTemplate(int connectTimeout, int readTimeout, boolean bypassSsl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            factory.setProxy(proxy);
        }

        RestTemplate restTemplate = new RestTemplate(factory);

        if (bypassSsl) {
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to bypass SSL verification", e);
            }
        }

        return restTemplate;
    }
}