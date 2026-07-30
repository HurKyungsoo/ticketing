package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 좌석. 동시성 실험의 주인공.
 * - 비관적 락: SeatRepository.findByIdForUpdate()
 * - 낙관적 락: @Version 필드
 * - 유니크 제약: SeatHold 테이블의 PK
 */
@Entity
@Table(
    name = "seat",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_seat", columnNames = {"schedule_id", "section", "rowNo", "seatNo"}),
    indexes = @Index(name = "idx_seat_schedule_status", columnList = "schedule_id, status")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Seat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private PerformanceSchedule schedule;

    /**
     * 구역(층). "1층 A", "합창석" 처럼 구역 이름만 담는다. 좌석도가 없는 기본 생성 홀도
     * A/B/C 로 갈리므로(SeatGenerator) 실질적으로 항상 값이 있다.
     *
     * <p>전에는 여기에 행까지 이어붙였다("1층A"). 행이 26줄을 넘으면
     * {@code (char)('A' + row)} 가 Z 를 지나 `[`, `\`, 소문자로 흘러가 구역명이 깨졌고,
     * 실제로 수집 데이터에서 228 회차 32,792 좌석이 그 상태였다. 행은 {@link #rowNo} 로 분리했다.
     */
    @Column(nullable = false, length = 20)
    private String section;

    /**
     * 줄 번호(열). 1부터 시작한다.
     *
     * <p>문자가 아니라 숫자인 이유: 문자열로 두면 정렬이 코드포인트 순이라 "10"이 "2"보다
     * 앞에 오고, 알파벳으로 두면 "AA"가 "B"보다 앞에 온다. 어느 쪽이든 고정폭 패딩이 필요하다.
     * 정수로 두면 {@code ORDER BY section, row_no, seat_no} 가 그대로 맞고 상한도 없다.
     */
    @Column(nullable = false)
    private int rowNo;

    @Column(nullable = false)
    private int seatNo;

    /**
     * 이 좌석 오른쪽에 통로가 있는지. 블록 경계에 해당하는 좌석에만 true 다.
     *
     * <p>전에는 화면이 "N석마다 통로"로 추정했다. 줄 길이가 같을 때만 성립하는 규칙이라
     * 부채꼴 구역에서는 통로가 세로로 어긋나 아예 뺄 수밖에 없었다. 통로 위치는 추정할 게
     * 아니라 좌석도가 아는 사실이므로 생성 시점에 좌석에 기록한다.
     */
    @Column(nullable = false)
    private boolean aisleAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(nullable = false)
    private int price;

    @Version
    private Long version;

    /** 어떤 예매에 묶여 있는지. 예매 1건이 좌석 여러 개를 가질 수 있어 FK 는 이 쪽(Seat)이 든다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    public boolean isAvailable() {
        return this.status == SeatStatus.AVAILABLE;
    }

    public void hold() {
        if (!isAvailable()) {
            throw new IllegalStateException("이미 선점되었거나 판매된 좌석입니다. seatId=" + id);
        }
        this.status = SeatStatus.HELD;
    }

    public void sell() {
        if (this.status != SeatStatus.HELD) {
            throw new IllegalStateException("선점 상태가 아닌 좌석은 판매할 수 없습니다. seatId=" + id);
        }
        this.status = SeatStatus.SOLD;
    }

    /** 선점 해제 + 만료/취소로 예매 연결도 함께 끊는다. */
    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.reservation = null;
    }

    public void assignReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    public String seatLabel() {
        return labelOf(section, rowNo, seatNo);
    }

    /**
     * 좌석 표기. 한국 공연장 관례대로 "구역 N열 M번" 으로 쓴다.
     * 구역이 없는 소극장은 구역을 빼고 "N열 M번".
     *
     * <p>좌석 배치도는 엔티티가 아니라 MyBatis DTO(SeatMapRow)로 조회하므로, 같은 표기를
     * 두 곳에서 만들지 않도록 여기 static 으로 둔다.
     */
    public static String labelOf(String section, int rowNo, int seatNo) {
        String row = rowNo + "열 " + seatNo + "번";
        return (section == null || section.isBlank()) ? row : section + " " + row;
    }
}
