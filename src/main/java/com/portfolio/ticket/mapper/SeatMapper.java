package com.portfolio.ticket.mapper;

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

    List<SalesStatRow> selectDailySales(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("performanceId") Long performanceId);
}
