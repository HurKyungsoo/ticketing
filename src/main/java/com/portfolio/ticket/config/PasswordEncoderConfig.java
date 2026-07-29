package com.portfolio.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityConfig 에서 분리했다. CustomOAuth2UserService 가 PasswordEncoder 를 필요로 하는데
 * SecurityConfig 가 CustomOAuth2UserService 를 생성자로 주입받다 보니, 같은 클래스에 두면
 * SecurityConfig -> CustomOAuth2UserService -> PasswordEncoder(SecurityConfig 의 @Bean) 순환이 생긴다.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
