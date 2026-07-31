package com.portfolio.ticket;

import com.portfolio.ticket.domain.Reservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 환불 수수료 규정. 요율을 {@code Reservation.REFUND_TIERS} 한 곳에 모으면서
 * 계산 결과가 종전과 같은지 못 박아 둔다.
 *
 * <p>이 규정은 공연 상세 화면이 같은 목록을 읽어 고지문을 그린다. 즉 여기가 깨지면
 * 사용자에게 안내한 수수료와 실제 청구액이 갈라진다 — 표기 오류가 아니라 금전 문제다.
 */
class RefundPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 10, 0);

    private int feeRateWithDaysLeft(long days) {
        return Reservation.builder().build().refundFeeRate(NOW.plusDays(days), NOW);
    }

    @DisplayName("남은 일수별 수수료율 — 10일 0% / 7일 10% / 3일 20% / 1일 30%")
    @ParameterizedTest(name = "{0}일 남음 -> {1}%")
    @CsvSource({
            "365, 0",
            "11,  0",
            "10,  0",
            "9,  10",   // 10일 미만으로 떨어지는 첫 지점
            "7,  10",
            "6,  20",   // 7일 미만
            "3,  20",
            "2,  30",   // 3일 미만
            "1,  30"
    })
    void feeRateByDaysLeft(long daysLeft, int expectedRate) {
        assertThat(feeRateWithDaysLeft(daysLeft)).isEqualTo(expectedRate);
    }

    @DisplayName("공연 당일(24시간 미만)은 취소 불가")
    @Test
    void sameDayCannotCancel() {
        assertThatThrownBy(() -> feeRateWithDaysLeft(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("당일");
    }

    /**
     * 화면 고지문이 이 목록을 그대로 읽어 그리므로, 순서가 뒤집히면 안내가 뒤죽박죽이 되고
     * {@code refundFeeRate} 의 위에서부터 훑는 방식도 깨진다(7일 단계가 10일보다 먼저 걸린다).
     */
    @DisplayName("REFUND_TIERS 는 남은 일수 내림차순 · 수수료율 오름차순이어야 한다")
    @Test
    void tiersAreOrdered() {
        var tiers = Reservation.REFUND_TIERS;
        assertThat(tiers).isNotEmpty();
        for (int i = 1; i < tiers.size(); i++) {
            assertThat(tiers.get(i).minDaysBefore())
                    .as("남은 일수는 내림차순이어야 한다")
                    .isLessThan(tiers.get(i - 1).minDaysBefore());
            assertThat(tiers.get(i).feeRate())
                    .as("수수료율은 오름차순이어야 한다")
                    .isGreaterThan(tiers.get(i - 1).feeRate());
        }
    }
}
