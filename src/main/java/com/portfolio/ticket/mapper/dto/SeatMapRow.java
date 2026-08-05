package com.portfolio.ticket.mapper.dto;

import com.portfolio.ticket.domain.Seat;
import lombok.Getter;
import lombok.Setter;

import java.util.Locale;
import java.util.Map;

@Getter
@Setter
public class SeatMapRow {
    private static final Map<String, String> STATUS_REASON = Map.of(
            "HELD", "결제 대기 중",
            "SOLD", "이미 판매된 좌석"
    );

    private Long seatId;
    private String section;
    private int rowNo;
    private int seatNo;
    /** 이 좌석 오른쪽에 통로가 있는지. 좌석도가 아는 사실이라 화면에서 추정하지 않는다. */
    private boolean aisleAfter;
    private String grade;
    private String status;
    private int price;

    /** "1층 3열 12번". 엔티티와 같은 표기를 쓰려고 Seat 의 규칙을 그대로 빌린다. */
    public String getLabel() {
        return Seat.labelOf(section, rowNo, seatNo);
    }

    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }

    /**
     * 좌석 위 호버 툴팁(데스크톱)이자 탭 안내(모바일, 토스트)의 문구.
     *
     * <p>고를 수 없는 좌석은 등급·가격 대신 안 되는 이유를 보여준다 — 어차피 살 수 없는
     * 이상 가격은 결정에 쓰이지 않는 정보다(화면 채움색도 같은 이유로 등급색을 무채색으로
     * 덮는다).
     */
    public String getTooltipText() {
        if (isAvailable()) {
            return grade + "석 · " + String.format(Locale.US, "%,d", price) + "원";
        }
        return STATUS_REASON.getOrDefault(status, "선택할 수 없는 좌석");
    }

    /**
     * 스크린리더용 접근 가능한 이름. 판매 완료·결제 대기 좌석은 {@code disabled} 속성을
     * 쓰지 않으므로(탭으로 사유를 안내하려면 클릭 이벤트가 살아 있어야 한다) 상태를
     * 이름에 직접 넣어야 스크린리더가 이유를 알 수 있다.
     */
    public String getAriaLabel() {
        if (isAvailable()) {
            return getLabel() + " " + grade + "석 " + String.format(Locale.US, "%,d", price) + "원";
        }
        return getLabel() + " " + getTooltipText();
    }
}
