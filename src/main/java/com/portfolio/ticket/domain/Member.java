package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "member",
    uniqueConstraints = @UniqueConstraint(name = "uk_member_login_id", columnNames = "loginId")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String loginId;

    /** BCrypt 인코딩된 값만 저장한다. */
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MemberRole role = MemberRole.USER;

    /**
     * 카카오/네이버 로그인 계정도 password 는 채워둔다(랜덤값 인코딩) — 폼 로그인 인증 흐름과 스키마를 그대로 공유하기 위함.
     * columnDefinition 에 DEFAULT 를 명시한 이유: 이미 회원이 있는 로컬 DB 에 ddl-auto=update 로
     * 이 컬럼을 처음 추가할 때, 기본값 없이 NOT NULL 컬럼을 추가하면 H2 가 기존 행 때문에
     * "NULL not allowed" 로 ALTER TABLE 자체를 거부한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'LOCAL'")
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    /** 소셜 로그인 제공자가 발급한 사용자 식별자. LOCAL 계정은 null. */
    @Column(length = 50)
    private String providerId;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
