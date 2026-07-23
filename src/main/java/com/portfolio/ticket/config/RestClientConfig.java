package com.portfolio.ticket.config;

import com.portfolio.ticket.payment.TossProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final TossProperties tossProperties;

    @Bean
    public RestClient publicDataRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** 토스페이먼츠 API 는 시크릿 키를 Basic Auth 의 아이디로, 비밀번호는 빈 문자열로 보낸다. */
    @Bean
    public RestClient tossRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        String encoded = Base64.getEncoder().encodeToString(
                (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                .baseUrl(tossProperties.getApiBaseUrl())
                .defaultHeader("Authorization", "Basic " + encoded)
                .requestFactory(factory)
                .build();
    }
}
