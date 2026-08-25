package com.portfolio.ticket.mapper;

import com.portfolio.ticket.mapper.dto.FacetCountRow;
import com.portfolio.ticket.mapper.dto.PerformanceFilter;
import com.portfolio.ticket.mapper.dto.NextShowRow;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 공연 목록 필터/집계. 조건이 7개(카테고리/월/시간대/진행상태/공연장/지역/키워드)라
 * JPA Specification 으로 풀면 <if> 동적 조합이 지저분해져서 MyBatis 로 뺐다.
 *
 * 카테고리/월/공연장별 건수는 옵션 하나마다 쿼리를 날리면 N번 나가므로, 각각
 * GROUP BY 로 한 번에 조회한다. 그 대신 각 select*Counts 는 "자기 자신의 필터"는
 * 빼고 나머지 조건만 반영한다 (예: selectCategoryCounts 는 category 필터를 무시해야
 * "다른 카테고리를 고르면 몇 건인지"가 나온다).
 */
public interface PerformanceMapper {

    List<PerformanceListRow> selectPerformances(@Param("f") PerformanceFilter filter);

    long countPerformances(@Param("f") PerformanceFilter filter);

    /**
     * 주어진 공연들의 "오늘 이후 가장 가까운 회차". 회차가 남지 않은 공연은 결과에 없다.
     *
     * <p>{@code performanceIds} 가 비어 있으면 부르지 말 것 — {@code IN ()} 은 문법 오류다.
     */
    List<NextShowRow> selectNextShowAt(@Param("performanceIds") List<Long> performanceIds,
                                        @Param("today") LocalDate today);

    List<FacetCountRow> selectCategoryCounts(@Param("f") PerformanceFilter filter);

    List<FacetCountRow> selectMonthCounts(@Param("f") PerformanceFilter filter);

    /** 건수 내림차순 전체 목록. "상위 N개 + 기타" 로 묶는 건 호출부(서비스/컨트롤러) 몫이다. */
    List<FacetCountRow> selectVenueCounts(@Param("f") PerformanceFilter filter);
}
