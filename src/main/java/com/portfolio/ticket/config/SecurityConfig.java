package com.portfolio.ticket.config;

import com.portfolio.ticket.security.CustomOAuth2UserService;
import com.portfolio.ticket.security.OAuth2ReturnToCaptureFilter;
import com.portfolio.ticket.security.PostLoginRedirectHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final PostLoginRedirectHandler postLoginRedirectHandler;
    private final OAuth2ReturnToCaptureFilter oAuth2ReturnToCaptureFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/", "/performances/**", "/schedules/**",
                        "/login", "/signup", "/css/**", "/img/**", "/images/**",
                        "/favicon.svg", "/favicon.ico", "/favicon-180.png",
                        "/api/admin/**", "/h2-console/**",
                        "/api/webhooks/**"
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
            // "/oauth2/authorization/{id}?returnTo=..." 가 카카오/네이버로 리다이렉트되기 전에
            // returnTo 를 세션에 먼저 저장해야 하므로, 그 리다이렉트를 실행하는 필터보다 앞에 둔다.
            .addFilterBefore(oAuth2ReturnToCaptureFilter, OAuth2AuthorizationRequestRedirectFilter.class)
            // h2-console 은 프레임 안에서 폼으로 동작하고, 웹훅은 토스 서버가, admin/sync 는
            // curl 등 외부에서 호출해서 셋 다 CSRF 토큰을 넣을 수 없으므로 예외 처리한다.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/api/webhooks/**", "/api/admin/**"))
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        return http.build();
    }
}
