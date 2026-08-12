package com.portfolio.ticket.config;

import com.portfolio.ticket.security.CustomOAuth2UserService;
import com.portfolio.ticket.security.OAuth2ReturnToCaptureFilter;
import com.portfolio.ticket.security.PostLoginRedirectHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.RequestCache;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final PostLoginRedirectHandler postLoginRedirectHandler;
    private final OAuth2ReturnToCaptureFilter oAuth2ReturnToCaptureFilter;
    /** 정의는 {@link RequestCacheConfig} 에 있다 — 여기 두면 핸들러와 순환 참조가 된다. */
    private final RequestCache requestCache;

    /**
     * 관리자 API 전용 체인. 브라우저 폼 로그인이 아니라 HTTP Basic 을 쓴다.
     *
     * 원래 "/api/admin/**" 가 permitAll 이라 누구나 POST /api/admin/sync 를 칠 수 있었다.
     * 이 API 는 한 번에 수백 건의 외부 호출을 일으키고 수 분간 도는 배치라, 열어두면
     * 반복 호출만으로 공공데이터 일일 한도를 소진시키고 DB 에 부하를 줄 수 있다.
     *
     * 세션을 만들지 않고(STATELESS) 매 요청 자격증명을 받으므로 CSRF 를 꺼도 안전하다.
     * 쿠키로 자동 전송되는 자격증명이 없어서 다른 사이트가 대신 호출할 수 없다.
     * 호출 예: curl -u {계정}:{비밀번호} -X POST http://localhost:8080/api/admin/sync
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
        // 진입점을 명시하지 않으면 미인증 요청이 폼 로그인 쪽으로 넘어가 302 /login 이 나간다.
        // API 클라이언트가 "인증 필요"와 "리다이렉트"를 구분할 수 없으므로 401 로 고정한다.
        BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("ticketing-admin");
        entryPoint.afterPropertiesSet();

        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
            .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/", "/performances/**", "/schedules/**",
                        // /fonts/** 를 빠뜨리면 자체 호스팅 웹폰트가 302 로 로그인 화면에 튕긴다.
                        // 글자는 폴백으로 보여서 화면이 깨진 티가 안 나므로 눈치채기 어렵다.
                        "/login", "/signup", "/password-reset", "/password-reset/**",
                        "/css/**", "/img/**", "/images/**", "/fonts/**",
                        // 스프링 부트의 시큐리티 필터는 REQUEST 뿐 아니라 ERROR 디스패치에도 걸린다.
                        // "/error" 를 열어두지 않으면 401/500 응답이 에러 페이지로 넘어가는 순간
                        // 인증 대상이 되어 로그인 리다이렉트(302)로 바뀐다 — 실제 원인이 가려진다.
                        "/error",
                        "/favicon.svg", "/favicon.ico", "/favicon-180.png",
                        // h2-console 은 local 프로파일에서만 등록된다(spring.h2.console.enabled).
                        // prod 에서는 서블릿 자체가 뜨지 않으므로 이 허용이 노출로 이어지지 않는다.
                        "/h2-console/**",
                        "/api/webhooks/**",
                        // 좌석도 실시간 갱신. 좌석도(/schedules/**)가 비로그인으로 보이는
                        // 화면이라 여기만 막으면 그 화면의 좌석 상태만 안 바뀐다. 돌려주는
                        // 것도 이미 그 화면에 그려져 있는 사실(어느 좌석이 나갔는지)뿐이다.
                        "/api/schedules/*/seat-status",
                        // 컨테이너/로드밸런서 헬스체크. management.endpoints.web.exposure.include 가
                        // health 하나뿐이라 다른 actuator 엔드포인트는 애초에 등록되지 않는다.
                        "/actuator/health"
                ).permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(postLoginRedirectHandler)
                .permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(postLoginRedirectHandler)
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            // 저장하는 쪽과 읽는 쪽(PostLoginRedirectHandler)이 같은 인스턴스를 쓰게 한다.
            .requestCache(cache -> cache.requestCache(requestCache))
            // "/oauth2/authorization/{id}?returnTo=..." 가 카카오/네이버로 리다이렉트되기 전에
            // returnTo 를 세션에 먼저 저장해야 하므로, 그 리다이렉트를 실행하는 필터보다 앞에 둔다.
            .addFilterBefore(oAuth2ReturnToCaptureFilter, OAuth2AuthorizationRequestRedirectFilter.class)
            // h2-console 은 프레임 안에서 폼으로 동작하고, 웹훅은 토스 서버가 호출해서
            // 둘 다 CSRF 토큰을 넣을 수 없으므로 예외 처리한다.
            // (/api/admin/** 는 위의 전용 체인이 담당하므로 여기서 다루지 않는다)
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/api/webhooks/**"))
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        return http.build();
    }
}
