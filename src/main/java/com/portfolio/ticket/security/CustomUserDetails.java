package com.portfolio.ticket.security;

import com.portfolio.ticket.domain.Member;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 가 인식하는 인증 주체. Member 를 감싼다.
 * 폼 로그인(UserDetails)과 카카오/네이버 로그인(OAuth2User) 이 같은 컨트롤러 코드에서
 * @AuthenticationPrincipal CustomUserDetails 로 동일하게 받아지도록 두 인터페이스를 모두 구현한다.
 */
@Getter
public class CustomUserDetails implements UserDetails, OAuth2User {

    private final Long memberId;
    private final String loginId;
    private final String password;
    private final String nickname;
    private final Collection<? extends GrantedAuthority> authorities;
    private final Map<String, Object> attributes;

    public CustomUserDetails(Member member) {
        this(member, Map.of());
    }

    public CustomUserDetails(Member member, Map<String, Object> attributes) {
        this.memberId = member.getId();
        this.loginId = member.getLoginId();
        this.password = member.getPassword();
        this.nickname = member.getNickname();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
        this.attributes = attributes;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public String getName() {
        return loginId;
    }
}
