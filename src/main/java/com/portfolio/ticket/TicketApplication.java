package com.portfolio.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableScheduling
@MapperScan("com.portfolio.ticket.mapper")
@SpringBootApplication
public class TicketApplication {

    /**
     * 공연 시각은 한국 시각 그대로 저장되고, 코드는 LocalDateTime.now() 로 지금 시각과 비교한다.
     * 서버 OS 는 UTC 라서 이 값을 고정하지 않으면 이미 시작한 회차를 9시간 동안 예정으로 판단한다.
     * 실행 환경의 설정에 기대지 않도록 다른 코드가 시각을 읽기 전, 클래스 로딩 시점에 고정한다.
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
