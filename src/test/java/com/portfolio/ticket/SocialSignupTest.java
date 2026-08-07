package com.portfolio.ticket;

import com.portfolio.ticket.domain.AuthProvider;
import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.MemberRole;
import com.portfolio.ticket.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 소셜 로그인 자동 회원가입 시 저장되는 값의 길이.
 *
 * <p><b>이 테스트가 생긴 이유.</b> 운영에서 네이버로 처음 로그인하면 통째로 실패하고 있었다
 * ({@code Data too long for column 'login_id'}). 소셜 가입은 login_id 를
 * {@code "<제공자>_<providerId>"} 로 만드는데(CustomOAuth2UserService) 컬럼이 varchar(30)
 * 이라, 네이버가 주는 긴 식별자가 들어가면 넘쳤다.
 *
 * <p><b>카카오는 멀쩡했다는 게 이 결함의 고약한 점이다.</b> 카카오 providerId 는 10 자리
 * 안팎 숫자라 16 자로 들어간다. 그래서 "소셜 로그인 됨"으로 보였고, 네이버로 <b>처음</b>
 * 가입하는 사람만 오류를 봤다 — 이미 가입된 계정은 조회만 하므로 재현되지도 않는다.
 *
 * <p>그래서 화면으로 확인하는 것을 믿을 수 없다. 여기서는 실제 소셜 응답 대신 <b>제공자가
 * 줄 수 있는 최대 길이</b>를 직접 저장해 본다 — 컬럼이 좁아지면 바로 깨진다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SocialSignupTest {

    /** {@code Member.providerId} 가 허용하는 최대 길이. login_id 는 여기에 접두어가 더 붙는다. */
    private static final int MAX_PROVIDER_ID = 50;

    @Autowired MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
    }

    @DisplayName("제공자 식별자가 최대 길이여도 소셜 계정이 저장된다")
    @Test
    void savesSocialMemberWithLongestProviderId() {
        String providerId = "N".repeat(MAX_PROVIDER_ID);
        // CustomOAuth2UserService.toNewMember 와 같은 규칙
        String loginId = AuthProvider.NAVER.name().toLowerCase() + "_" + providerId;

        assertThatCode(() -> memberRepository.saveAndFlush(socialMember(loginId, providerId)))
                .as("네이버 신규 가입이 여기서 'Data too long' 으로 막혔었다")
                .doesNotThrowAnyException();

        assertThat(memberRepository.findByAuthProviderAndProviderId(AuthProvider.NAVER, providerId))
                .as("저장한 값 그대로 다시 찾을 수 있어야 한다 — 잘려 들어가면 조회가 어긋난다")
                .isPresent()
                .get()
                .satisfies(m -> assertThat(m.getLoginId()).isEqualTo(loginId));
    }

    /**
     * 잘라 넣는 방식으로 "고치면" 안 된다는 걸 못 박는다. providerId 앞부분이 같은 두 계정이
     * 같은 login_id 로 접혀 유니크 제약에 걸리면, 뒤에 온 사람은 가입이 막히거나 — 더 나쁘게 —
     * 조회 방식에 따라 남의 계정에 붙는다.
     */
    @DisplayName("앞부분이 같고 뒤만 다른 식별자 둘이 각각 저장된다")
    @Test
    void distinguishesProviderIdsSharingAPrefix() {
        String common = "N".repeat(MAX_PROVIDER_ID - 1);
        String first = common + "A";
        String second = common + "B";

        memberRepository.saveAndFlush(socialMember("naver_" + first, first));
        memberRepository.saveAndFlush(socialMember("naver_" + second, second));

        assertThat(memberRepository.count()).isEqualTo(2);
        assertThat(memberRepository.findByAuthProviderAndProviderId(AuthProvider.NAVER, first))
                .get()
                .satisfies(m -> assertThat(m.getLoginId()).endsWith("A"));
    }

    private Member socialMember(String loginId, String providerId) {
        return Member.builder()
                .loginId(loginId)
                .password("{noop}pw")
                .nickname("네이버사용자")
                .role(MemberRole.USER)
                .authProvider(AuthProvider.NAVER)
                .providerId(providerId)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
