package com.portfolio.ticket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 ID 가 링크에 붙으면 크롤러가 그 주소를 수집하고, 링크를 공유할 때 세션도 함께 넘어간다.
 * 주소 변환은 실제 서블릿 컨테이너에서만 일어나 MockMvc 로는 잡히지 않으므로 서버를 띄워 검사한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionTrackingTest {

    @Autowired
    TestRestTemplate restTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/signup", "/nonexistent"})
    void 처음_접속해도_링크에_세션_ID_가_붙지_않는다(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));

        ResponseEntity<String> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getBody()).contains("site-footer");
        assertThat(response.getBody()).doesNotContain("jsessionid");
    }
}
