package com.portfolio.ticket.security;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 카카오/네이버 로그인 성공 후 사용자 정보를 가져와 Member 로 매핑한다.
 * 처음 로그인하는 사용자는 이 시점에 자동으로 회원가입 처리한다(별도 가입 폼 없음).
 */
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuthAttributes attrs = OAuthAttributes.of(registrationId, oAuth2User.getAttributes());
        Member member = memberRepository.findByAuthProviderAndProviderId(attrs.provider(), attrs.providerId())
                .orElseGet(() -> memberRepository.save(toNewMember(attrs)));

        return new CustomUserDetails(member, oAuth2User.getAttributes());
    }

    private Member toNewMember(OAuthAttributes attrs) {
        String loginId = attrs.provider().name().toLowerCase() + "_" + attrs.providerId();
        String nickname = attrs.nickname().length() > 20 ? attrs.nickname().substring(0, 20) : attrs.nickname();

        // 소셜 계정은 비밀번호로 로그인하지 않지만, password 컬럼은 NOT NULL 이라 폼 로그인과
        // 스키마를 공유하기 위해 추측 불가능한 랜덤값을 인코딩해 채워둔다.
        return Member.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .nickname(nickname)
                .authProvider(attrs.provider())
                .providerId(attrs.providerId())
                .createdAt(LocalDateTime.now())
                .build();
    }
}
