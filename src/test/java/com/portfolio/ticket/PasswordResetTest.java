package com.portfolio.ticket;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.PasswordResetToken;
import com.portfolio.ticket.repository.MemberRepository;
import com.portfolio.ticket.repository.PasswordResetTokenRepository;
import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비밀번호 재설정. 계정 존재 여부가 요청 결과로 드러나면 안 되고(계정 열람 공격),
 * 토큰은 한 번만·만료 전에만 써야 한다는 두 가지가 이 기능의 핵심 불변식이다.
 * SMTP 가 없는 테스트 환경에서도(spring.mail.host=REPLACE_ME) 발송 실패가
 * 토큰 생성 자체를 막지 않아야 한다 — PasswordResetMailSender 가 예외를 삼킨다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTest {

    @Autowired PasswordResetService passwordResetService;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordResetTokenRepository tokenRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Long memberId;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        memberRepository.deleteAll();

        memberId = memberRepository.save(Member.builder()
                .loginId("resettarget")
                .password(passwordEncoder.encode("oldpassword"))
                .nickname("재설정대상")
                .email("target@example.com")
                .createdAt(LocalDateTime.now())
                .build()).getId();
    }

    @DisplayName("아이디·이메일이 일치하면 토큰이 생성된다(메일 발송 실패와 무관하게)")
    @Test
    void requestResetCreatesTokenOnMatch() {
        passwordResetService.requestReset("resettarget", "target@example.com", "http://localhost/password-reset/");

        assertThat(tokenRepository.findAll()).hasSize(1);
        assertThat(tokenRepository.findAll().get(0).getMemberId()).isEqualTo(memberId);
    }

    @DisplayName("이메일이 안 맞으면 조용히 끝난다 — 계정 존재 여부가 응답으로 드러나면 안 된다")
    @Test
    void requestResetSilentlyNoOpsOnMismatch() {
        passwordResetService.requestReset("resettarget", "wrong@example.com", "http://localhost/password-reset/");

        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @DisplayName("없는 아이디도 예외 없이 조용히 끝난다")
    @Test
    void requestResetSilentlyNoOpsOnUnknownLoginId() {
        passwordResetService.requestReset("nobody", "target@example.com", "http://localhost/password-reset/");

        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @DisplayName("유효한 토큰으로 재설정하면 비밀번호가 바뀌고 토큰은 다시 못 쓴다")
    @Test
    void resetPasswordChangesPasswordAndConsumesToken() {
        passwordResetService.requestReset("resettarget", "target@example.com", "http://localhost/password-reset/");
        String token = tokenRepository.findAll().get(0).getToken();

        passwordResetService.resetPassword(token, "newpassword");

        Member reloaded = memberRepository.findById(memberId).orElseThrow();
        assertThat(passwordEncoder.matches("newpassword", reloaded.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("oldpassword", reloaded.getPassword())).isFalse();

        assertThatThrownBy(() -> passwordResetService.resetPassword(token, "anotherpassword"))
                .as("이미 쓴 토큰은 다시 못 쓴다")
                .isInstanceOf(NotFoundException.class);
    }

    @DisplayName("만료된 토큰은 재설정에 쓸 수 없다")
    @Test
    void expiredTokenIsRejected() {
        String token = "expired-token";
        tokenRepository.save(PasswordResetToken.builder()
                .memberId(memberId)
                .token(token)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .createdAt(LocalDateTime.now().minusMinutes(31))
                .build());

        assertThat(passwordResetService.isValidToken(token)).isFalse();
        assertThatThrownBy(() -> passwordResetService.resetPassword(token, "newpassword"))
                .isInstanceOf(NotFoundException.class);

        Member reloaded = memberRepository.findById(memberId).orElseThrow();
        assertThat(passwordEncoder.matches("oldpassword", reloaded.getPassword())).isTrue();
    }

    @DisplayName("모르는 토큰은 재설정에 쓸 수 없다")
    @Test
    void unknownTokenIsRejected() {
        assertThat(passwordResetService.isValidToken("no-such-token")).isFalse();
        assertThatThrownBy(() -> passwordResetService.resetPassword("no-such-token", "newpassword"))
                .isInstanceOf(NotFoundException.class);
    }
}
