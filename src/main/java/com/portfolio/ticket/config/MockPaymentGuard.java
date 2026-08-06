package com.portfolio.ticket.config;

import com.portfolio.ticket.payment.TossProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 테스트용 즉시 결제({@code toss.mock-enabled})가 운영 환경에서 켜진 채로 뜨는 것을 막는다.
 *
 * <p>이 플래그가 켜지면 결제 수단 승인 없이 예매가 확정된다. 로컬에서 결제 다음 화면들을
 * 확인하려고 둔 장치라, 운영에 새면 <b>아무나 돈을 안 내고 좌석을 확정</b>할 수 있다.
 *
 * <p>이미 이중으로 막혀 있기는 하다 — 기본값이 false 고, 켜는 선언은 application.yml 의
 * local 프로파일 블록 안에만 있다. 그런데 그 둘 다 <b>"prod 프로파일로 떴다"는 것을 확인하지
 * 않는다.</b> 실제로 뚫리는 경로가 둘 있다.
 * <ul>
 *   <li>배포 서버에서 {@code SPRING_PROFILES_ACTIVE} 를 안 주고 띄우는 경우. 그러면 local 도
 *       prod 도 아닌 기본 상태로 뜨는데, 이때 누군가 {@code TOSS_MOCK=true} 를 넘기면 켜진다.
 *   <li>운영 장비에서 로그를 보려고 잠깐 local 프로파일로 띄우는 경우.
 * </ul>
 *
 * <p>그래서 "켜져 있는데 local 이 아니면" 기동을 거부한다. 조용히 무시하고 끄는 방법도 있지만
 * 그러면 설정이 틀린 채로 서비스가 떠 있게 된다 — 배포 설정 실수는 시끄럽게 실패하는 편이 낫다.
 *
 * <p>검사 시점을 기동 완료 직후({@link ApplicationReadyEvent})로 둔 이유는 프로파일과 프로퍼티가
 * 모두 확정된 뒤여야 하기 때문이다. 여기서 예외를 던지면 애플리케이션이 그대로 죽는다.
 */
@Component
@RequiredArgsConstructor
public class MockPaymentGuard implements ApplicationListener<ApplicationReadyEvent> {

    /** 이 프로파일에서만 테스트 결제를 허용한다. */
    private static final String ALLOWED_PROFILE = "local";

    private final TossProperties tossProperties;
    private final Environment environment;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!tossProperties.isMockEnabled()) {
            return;
        }

        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains(ALLOWED_PROFILE)) {
            return;
        }

        throw new IllegalStateException("""
                테스트용 즉시 결제(toss.mock-enabled)가 켜져 있는데 활성 프로파일이 %s 가 아닙니다: %s
                이 상태로는 결제 없이 예매가 확정됩니다. 배포 환경이라면 SPRING_PROFILES_ACTIVE=prod 를 주거나
                TOSS_MOCK=false 로 끄고 다시 띄우세요."""
                .formatted(ALLOWED_PROFILE, active.isEmpty() ? "(없음)" : active));
    }
}
