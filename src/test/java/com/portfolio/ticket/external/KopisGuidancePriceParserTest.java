package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SeatGrade;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가격 문구(pcseguidance) 해석. 문구는 전부 운영 DB 에 실제로 들어온 것이다.
 * 우리 등급(VIP/R/S/A)으로 못 읽던 문구가 기준가 비율로 만든 네 등급이 되어,
 * 원문에 없는 등급과 가격의 좌석이 생기던 문제(2026-11-13 아침 점검)를 막는다.
 */
class KopisGuidancePriceParserTest {

    private static Map<SeatGrade, Integer> parse(String raw) {
        return KopisGuidanceParser.pricesByGrade(raw);
    }

    @Test
    void 층별_가격은_비싼_층을_앞줄_등급으로_만든다() {
        assertThat(parse("1층석 10,000원, 2층석 5,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.R, 10_000, SeatGrade.S, 5_000));
        assertThat(parse("특별석  66,000원, 일반석  55,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.R, 66_000, SeatGrade.S, 55_000));
    }

    @Test
    void 가격이_하나면_전석과_같이_S석_하나다() {
        assertThat(parse("스탠딩 89,000원")).containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 89_000));
        assertThat(parse("1층석 10,000원")).containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 10_000));
        assertThat(parse("C석 88,000원")).containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 88_000));
        assertThat(parse("어린이+ 보호자 40,000원")).containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 40_000));
        assertThat(parse("스탠딩 88,000원, 지정석 88,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 88_000));
    }

    @Test
    void 판매_경로별_가격은_등급이_아니라_정가_하나다() {
        assertThat(parse("현장판매 50,000원, 사전예약 44,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 50_000));
        assertThat(parse("사전예약 20,000원, 현장판매 25,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 25_000));
        assertThat(parse("사전예약 49,900원")).containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 49_900));
        assertThat(parse("1인권 판매석(예매가) 14,900원, 특가 판매석(예매가) 9,900원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 14_900));
        assertThat(parse("네이버 1인권 판매석 14,900원, 네이버 특가 판매석 9,900원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 14_900));
    }

    @Test
    void 좌석_가격과_판매_경로_가격이_섞이면_좌석_가격만_쓴다() {
        assertThat(parse("스탠딩  66,000원, 현장판매 77,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 66_000));
        assertThat(parse("무등급석 10,000원, 휠체어석 5,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 10_000));
    }

    @Test
    void 이름이_같은_여러_금액은_고르는_금액이라_가장_높은_값_하나다() {
        assertThat(parse("관람료선택제 1,000원, 관람료선택제 3,000원, 관람료선택제 5,000원, 관람료선택제 10,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 10_000));
    }

    @Test
    void 세_네_가격은_등급_수만큼_만든다() {
        assertThat(parse("VIP 스탠딩석 169,000원, GA 스탠딩석 119,000원, GA 지정석 109,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.R, 169_000, SeatGrade.S, 119_000, SeatGrade.A, 109_000));
        assertThat(parse("VIP 패키지석 260,000원, CAT 1 167,000원, CAT 2 128,000원, CAT 3  68,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SeatGrade.VIP, 260_000, SeatGrade.R, 167_000, SeatGrade.S, 128_000, SeatGrade.A, 68_000));
    }

    @Test
    void 우리_등급_뒤에_괄호_설명이_붙어도_등급으로_읽는다() {
        assertThat(parse("VIP석(1층)  77,000원, S석(2층)  55,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.VIP, 77_000, SeatGrade.S, 55_000));
    }

    @Test
    void SR석은_R석에_덮이지_않고_VIP_자리에_든다() {
        assertThat(parse("SR석 154,000원, R석 143,000원, S석 132,000원, 휠체어석 132,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SeatGrade.VIP, 154_000, SeatGrade.R, 143_000, SeatGrade.S, 132_000));
        assertThat(parse("VIP석 200,000원, SR석 154,000원, R석 143,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.VIP, 200_000, SeatGrade.R, 143_000));
    }

    @Test
    void 종전에_읽던_문구는_그대로다() {
        assertThat(parse("VIP석 165,000원, R석 143,000원, S석 121,000원, A석 99,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SeatGrade.VIP, 165_000, SeatGrade.R, 143_000, SeatGrade.S, 121_000, SeatGrade.A, 99_000));
        assertThat(parse("R석 66,000원, S석 44,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.R, 66_000, SeatGrade.S, 44_000));
        assertThat(parse("전석 40,000원, 사전예약 15,000원"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(SeatGrade.S, 40_000));
    }

    @Test
    void 금액이_없으면_null_이라_무료_안내와_기준가_계산이_그대로다() {
        assertThat(parse("전석무료")).isNull();
        assertThat(parse("전석")).isNull();
        assertThat(parse(null)).isNull();
    }
}
