package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.PasswordResetToken;
import com.portfolio.ticket.mail.PasswordResetMailSender;
import com.portfolio.ticket.repository.MemberRepository;
import com.portfolio.ticket.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 비밀번호 재설정. 계정 존재 여부가 응답으로 드러나면 계정 열람(존재 확인) 공격에
 * 노출되므로, {@link #requestReset} 은 아이디/이메일이 안 맞아도 예외를 던지지 않고
 * 조용히 끝난다 — 호출부(컨트롤러)는 성공이든 아니든 항상 같은 안내 화면을 보여줘야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final MemberRepository memberRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordResetMailSender mailSender;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void requestReset(String loginId, String email, String resetUrlPrefix) {
        Optional<Member> member = memberRepository.findByLoginIdAndEmail(loginId, email);
        if (member.isEmpty()) {
            log.info("비밀번호 재설정 요청 - 일치하는 계정 없음. loginId={}", loginId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenRepository.save(PasswordResetToken.builder()
                .memberId(member.get().getId())
                .token(token)
                .expiresAt(now.plus(TOKEN_TTL))
                .createdAt(now)
                .build());

        mailSender.send(email, resetUrlPrefix + token);
    }

    @Transactional(readOnly = true)
    public boolean isValidToken(String token) {
        return findValidToken(token).isPresent();
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = findValidToken(token)
                .orElseThrow(() -> new NotFoundException("만료되었거나 이미 사용된 링크입니다."));

        Member member = memberRepository.findById(resetToken.getMemberId())
                .orElseThrow(() -> new NotFoundException("계정을 찾을 수 없습니다."));

        member.changePassword(passwordEncoder.encode(newPassword));
        resetToken.markUsed(LocalDateTime.now());
    }

    private Optional<PasswordResetToken> findValidToken(String token) {
        return tokenRepository.findByToken(token)
                .filter(t -> t.isValid(LocalDateTime.now()));
    }
}
