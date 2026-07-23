package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Long signup(String loginId, String password, String nickname) {
        if (memberRepository.existsByLoginId(loginId)) {
            throw new IllegalStateException("이미 사용 중인 아이디입니다. loginId=" + loginId);
        }

        Member member = memberRepository.save(Member.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .createdAt(LocalDateTime.now())
                .build());

        return member.getId();
    }
}
