package com.portfolio.ticket.mapper;

import com.portfolio.ticket.mapper.dto.ReviewStatRow;
import org.apache.ibatis.annotations.Param;

/**
 * 관람평 집계. CRUD 는 JPA(ReviewRepository)가 하고 여기는 집계만 맡는다 —
 * 프로젝트의 JPA/MyBatis 역할 분리 기준(동적·집계 쿼리는 MyBatis) 그대로다.
 */
public interface ReviewMapper {

    /** 한 공연의 관람평 개수·평균. 관람평이 없으면 count 0, average null 이다. */
    ReviewStatRow selectReviewStat(@Param("performanceId") Long performanceId);
}
