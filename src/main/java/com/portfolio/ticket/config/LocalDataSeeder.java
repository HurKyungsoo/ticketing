package com.portfolio.ticket.config;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.MemberRole;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.PerformanceCategoryResolver;
import com.portfolio.ticket.repository.MemberRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.service.SeatGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 공공데이터 API 키가 없어도 로컬에서 화면을 확인할 수 있도록 공연/회차/좌석/회원을
 * 미리 채워두는 로컬 전용 시드 데이터. 실제 수집 데이터와 섞이지 않도록
 * externalId 에 "SEED-" 접두어를 붙인다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalDataSeeder implements CommandLineRunner {

    private static final int SCHEDULE_COUNT = 3;
    private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

    private final PerformanceRepository performanceRepository;
    private final SeatGenerator seatGenerator;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final PerformanceCategoryResolver categoryResolver;

    @Override
    @Transactional
    public void run(String... args) {
        seedPerformances();
        seedMembers();
    }

    private void seedPerformances() {
        if (performanceRepository.count() > 0) {
            log.info("이미 공연 데이터가 있어 로컬 시드를 건너뜁니다.");
            return;
        }

        seedPerformance("SEED-MUSICAL-1", "라이트 인 더 스카이", "뮤지컬", "블루스퀘어", 400, 120_000);
        seedPerformance("SEED-PLAY-1", "안녕, 그 여름", "연극", "대학로 예술극장", 150, 45_000);
        seedPerformance("SEED-CLASSIC-1", "필하모닉 정기연주회", "클래식", "예술의전당 콘서트홀", 300, 80_000);

        log.info("로컬 시드 공연 3개 생성 완료 (공연당 회차 {}개)", SCHEDULE_COUNT);
    }

    private void seedPerformance(String externalId, String title, String genre, String venue,
                                  int totalSeatCount, int basePrice) {
        Performance performance = Performance.builder()
                .externalId(externalId)
                .sourceType(SourceType.SEED)
                .title(title)
                .genre(genre)
                .category(categoryResolver.resolve(genre))
                .venue(venue)
                .address("서울")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(60))
                .totalSeatCount(totalSeatCount)
                .basePrice(basePrice)
                .build();

        for (int i = 1; i <= SCHEDULE_COUNT; i++) {
            performance.addSchedule(PerformanceSchedule.builder()
                    .showAt(LocalDateTime.of(LocalDate.now().plusDays(i * 7L), SHOW_TIME))
                    .totalSeats(totalSeatCount)
                    .remainingSeats(totalSeatCount)
                    .build());
        }

        performanceRepository.saveAndFlush(performance);

        for (PerformanceSchedule schedule : performance.getSchedules()) {
            seatGenerator.generate(schedule.getId(), venue, totalSeatCount, basePrice);
        }
    }

    private void seedMembers() {
        seedMember("user", "user", "일반회원", MemberRole.USER);
        seedMember("admin", "admin", "관리자", MemberRole.ADMIN);
    }

    private void seedMember(String loginId, String password, String nickname, MemberRole role) {
        if (memberRepository.existsByLoginId(loginId)) {
            return;
        }
        memberRepository.save(Member.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .role(role)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("로컬 시드 회원 생성. loginId={}, role={}", loginId, role);
    }
}
