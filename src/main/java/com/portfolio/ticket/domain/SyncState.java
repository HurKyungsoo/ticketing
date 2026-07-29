package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 소스별 마지막 수집 성공 일자. KOPIS 증분 수집(afterdate)의 기준점이 된다.
 *
 * 행이 없으면 "아직 한 번도 성공한 적 없음" 이므로 전체 수집을 한다.
 * afterdate 가 일(日) 단위라서 시각이 아니라 날짜만 들고 있으면 충분하다.
 */
@Entity
@Table(name = "sync_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SyncState {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    private SourceType sourceType;

    @Column(nullable = false)
    private LocalDate lastSyncedOn;

    public void markSyncedOn(LocalDate date) {
        this.lastSyncedOn = date;
    }
}
