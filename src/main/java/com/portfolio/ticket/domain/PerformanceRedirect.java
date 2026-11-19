package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 합쳐서 지운 공연의 옛 번호 → 남긴 공연 번호.
 *
 * <p>같은 공연이 KOPIS 와 문화정보에서 따로 들어오면 하나만 남기고 지운다(PerformanceRemover).
 * 지운 쪽 주소는 최근 본 공연·공유한 링크·검색엔진에 그대로 남아 있어서, 전에는 404 만 떴다 —
 * 공연은 멀쩡히 있는데 "없는 공연"으로 보였다. 이 기록으로 상세 화면이 남긴 공연으로 보낸다.
 *
 * <p>공연을 연관관계로 들지 않는다. 지운 쪽은 이미 없고, 남긴 쪽도 나중에 지워질 수 있다.
 */
@Entity
@Table(name = "performance_redirect",
       indexes = @Index(name = "idx_performance_redirect_to", columnList = "to_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PerformanceRedirect {

    @Id
    @Column(name = "from_id")
    private Long fromId;

    @Column(name = "to_id", nullable = false)
    private Long toId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
