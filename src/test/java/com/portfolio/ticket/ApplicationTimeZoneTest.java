package com.portfolio.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** CI 와 운영 서버는 OS 시간대가 UTC 라서, 앱이 스스로 고정하지 않으면 이 테스트가 실패한다. */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationTimeZoneTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void 앱의_현재_시각은_OS_설정과_무관하게_한국_시각이다() {
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(SEOUL);
        assertThat(Duration.between(LocalDateTime.now(), LocalDateTime.now(SEOUL)).abs())
                .isLessThan(Duration.ofSeconds(5));
    }
}
