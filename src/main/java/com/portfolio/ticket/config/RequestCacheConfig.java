package com.portfolio.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * 인증 때문에 막힌 요청을 담아두는 곳. 로그인 성공 후 그 화면으로 되돌려 보내는 데 쓴다
 * (com.portfolio.ticket.security.PostLoginRedirectHandler).
 *
 * <p><b>SecurityConfig 가 아니라 여기 두는 이유.</b> SecurityConfig 는 생성자로
 * PostLoginRedirectHandler 를 받는데, 그 핸들러가 이 빈을 필요로 한다. 이 빈을
 * SecurityConfig 안에 두면 "SecurityConfig → 핸들러 → SecurityConfig" 순환이 되어
 * 컨텍스트가 아예 안 뜬다(실제로 그렇게 만들었다가 기동이 막혔다).
 *
 * <p><b>기본 구현을 그대로 안 쓰는 이유.</b> 스프링 6.1 부터 저장된 요청의 주소 끝에
 * {@code ?continue} 를 붙여 "되돌아온 요청"임을 표시한다. 우리는 핸들러가 저장된 요청을
 * 직접 읽고 지우므로 그 표식이 쓰일 일이 없는데, 켜 두면 로그인 직후 주소창에
 * {@code /mypage/reservations?continue} 가 그대로 남는다 — 화면은 정상인데 주소만 이상하다.
 *
 * <p>저장하는 쪽(ExceptionTranslationFilter)과 읽는 쪽(핸들러)이 <b>같은 인스턴스</b>를
 * 쓰게 SecurityConfig 에서 {@code .requestCache(...)} 로 물려준다. 각자 new 하면 설정이
 * 갈릴 자리가 생긴다.
 */
@Configuration
public class RequestCacheConfig {

    @Bean
    public RequestCache requestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setMatchingRequestParameterName(null);
        return cache;
    }
}
