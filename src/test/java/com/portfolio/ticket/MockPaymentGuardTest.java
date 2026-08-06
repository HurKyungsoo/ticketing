package com.portfolio.ticket;

import com.portfolio.ticket.config.MockPaymentGuard;
import com.portfolio.ticket.payment.TossProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테스트용 즉시 결제가 운영에 새지 않는지.
 *
 * <p>{@code toss.mock-enabled} 가 켜지면 결제 수단 승인 없이 예매가 확정된다. 배포 환경에서
 * 켜진 채로 뜨면 아무나 돈을 안 내고 좌석을 확정할 수 있으므로, 그 조합에서는 기동 자체가
 * 실패해야 한다.
 *
 * <p>이 가드가 없어도 평소엔 아무 일도 안 일어난다(기본값 false + local 프로파일에만 선언).
 * 그래서 <b>가드가 사라져도 어떤 화면도 안 깨진다</b> — 누가 지우거나 조건을 뒤집어도
 * 배포해서 결제를 시도해보기 전까지는 모른다. 그런 종류의 안전장치는 테스트로 고정해 둬야
 * 의미가 있다.
 *
 * <p>스프링 컨텍스트를 띄우지 않고 리스너를 직접 부른다 — 확인하려는 건 "이 조건에서
 * 예외를 던지는가" 하나뿐이고, 프로파일 조합마다 컨텍스트를 새로 띄우면 느리기만 하다.
 */
class MockPaymentGuardTest {

    private static final ApplicationReadyEvent IGNORED_EVENT = null;

    private MockPaymentGuard guard(boolean mockEnabled, String... activeProfiles) {
        TossProperties props = new TossProperties();
        props.setMockEnabled(mockEnabled);

        MockEnvironment env = new MockEnvironment();
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }
        return new MockPaymentGuard(props, env);
    }

    @DisplayName("prod 프로파일에서 테스트 결제가 켜져 있으면 기동을 거부한다")
    @Test
    void refusesToStartOnProd() {
        assertThatThrownBy(() -> guard(true, "prod").onApplicationEvent(IGNORED_EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toss.mock-enabled")
                .hasMessageContaining("prod");
    }

    /**
     * 가장 뚫리기 쉬운 경로다 — 배포 서버에서 SPRING_PROFILES_ACTIVE 를 안 주면 prod 도
     * local 도 아닌 상태로 뜬다. "prod 일 때만 막는다" 로 짰으면 여기가 그대로 통과한다.
     */
    @DisplayName("프로파일이 아예 없는 상태에서도 거부한다 (SPRING_PROFILES_ACTIVE 누락)")
    @Test
    void refusesToStartWithoutAnyProfile() {
        assertThatThrownBy(() -> guard(true).onApplicationEvent(IGNORED_EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(없음)");
    }

    @DisplayName("local 프로파일에서는 켜져 있어도 정상 기동한다")
    @Test
    void allowsOnLocal() {
        assertThatCode(() -> guard(true, "local").onApplicationEvent(IGNORED_EVENT))
                .doesNotThrowAnyException();
    }

    @DisplayName("꺼져 있으면 어떤 프로파일에서도 간섭하지 않는다")
    @Test
    void ignoresWhenDisabled() {
        assertThatCode(() -> guard(false, "prod").onApplicationEvent(IGNORED_EVENT))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard(false).onApplicationEvent(IGNORED_EVENT))
                .doesNotThrowAnyException();
    }

    @DisplayName("기본값은 꺼짐 — 프로퍼티를 안 주면 아무 데서도 안 켜진다")
    @Test
    void defaultsToDisabled() {
        assertThat(new TossProperties().isMockEnabled()).isFalse();
    }
}
