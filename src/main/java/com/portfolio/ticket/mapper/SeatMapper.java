package com.portfolio.ticket.mapper;

import com.portfolio.ticket.mapper.dto.CancelStatsRow;
import com.portfolio.ticket.mapper.dto.GradeSalesRow;
import com.portfolio.ticket.mapper.dto.PerformanceSalesRow;
import com.portfolio.ticket.mapper.dto.SeatMapRow;
import com.portfolio.ticket.mapper.dto.SalesStatRow;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 좌석 배치도 조회와 매출 집계는 JPA 로 풀면 N+1 이 나거나 쿼리가 지저분해져
 * MyBatis 로 뺐다. (정적 CRUD 는 JPA, 동적/집계 쿼리는 MyBatis)
 */
public interface SeatMapper {

    List<SeatMapRow> selectSeatMap(@Param("scheduleId") Long scheduleId);

    /** 일자별 매출 집계. 관리자 대시보드의 라인 차트이자, 요약 카드(총 매출/총 건수/객단가)의 원천이기도 하다. */
    List<SalesStatRow> selectDailySales(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("performanceId") Long performanceId);

    /** 공연별 매출 TOP N (막대 차트). */
    List<PerformanceSalesRow> selectTopPerformances(@Param("from") LocalDate from,
                                                     @Param("to") LocalDate to,
                                                     @Param("limit") int limit);

    /** 좌석 등급별 판매 비중 (도넛 차트). */
    List<GradeSalesRow> selectSalesByGrade(@Param("from") LocalDate from,
                                            @Param("to") LocalDate to,
                                            @Param("performanceId") Long performanceId);

    /** 취소율 계산용. 확정/취소 모두 예매 생성일(created_at) 기준으로 세어야 취소분도 잡힌다
     *  (취소 건은 결제 전에 취소됐다면 confirmed_at 이 없다). */
    CancelStatsRow selectCancelStats(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("performanceId") Long performanceId);
}
