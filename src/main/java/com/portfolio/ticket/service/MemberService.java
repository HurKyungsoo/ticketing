package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MemberService {

    /** RFC 완전 검증이 아니라 오타를 걸러내는 수준이면 충분하다 — 실제 도달 확인은 발송 결과가 한다. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Long signup(String loginId, String password, String nickname, String email) {
        if (memberRepository.existsByLoginId(loginId)) {
            throw new IllegalStateException("이미 사용 중인 아이디입니다. loginId=" + loginId);
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalStateException("이메일 형식이 올바르지 않습니다.");
        }
        if (memberRepository.existsByEmail(email)) {
            throw new IllegalStateException("이미 사용 중인 이메일입니다.");
        }

        Member member = memberRepository.save(Member.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .email(email)
                .createdAt(LocalDateTime.now())
                .build());

        return member.getId();
    }
}
