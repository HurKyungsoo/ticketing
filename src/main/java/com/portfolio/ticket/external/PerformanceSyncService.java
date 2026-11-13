package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.service.PerformanceRegisteredEvent;
import com.portfolio.ticket.service.PerformanceRemover;
import com.portfolio.ticket.service.ScheduleOpenedEvent;
import com.portfolio.ticket.service.SeatGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 수집 → 정규화 → upsert → 회차/좌석 생성.
 *
 * API 원본에는 "회차" 개념이 없어서, 공연 기간 안에서 회차를 만드는 규칙을 둔다.
 * 시각은 KOPIS 시간대별 상연 통계 분포를 따른다. (README 의 데이터 가공 규칙 참고)
 *
 * <p>신규 수집 시에는 오늘(또는 시작일, 더 늦은 쪽)부터 종료일까지 한 번에 다 만든다.
 * 이미 있는 공연은 매 동기화마다 {@link #topUpSchedules} 로 마지막 회차 다음 날부터
 * 종료일까지 빠진 만큼만 추가한다 — 과거 회차는 그대로 두고 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceSyncService {

    private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

    /**
     * 이보다 기간이 긴 항목은 상설전시류(박물관 상설전시관 등)로 보고 제외한다.
     * 회차 생성이 startDate 기준 연속 8일이라, 기간이 몇 년씩 되는 항목은
     * 이미 다 지난 날짜로만 회차가 만들어져 예매가 불가능해진다.
     */
    private static final long MAX_RUN_DAYS = 90;

    /**
     * 공연이 아닌 항목의 제목 표지. 문화정보 소스에는 공연과 함께 참여자 모집 공고가 섞여 들어온다
     * ("[강북문화재단] 2026 블로그 서포터즈 2기 모집" 같은 것). 예매할 대상이 아니므로 수집하지 않는다.
     *
     * <p><b>넓게 잡지 않은 이유.</b> 실데이터 878건으로 후보를 세어보고 정했다 — "공모"는 5건 중
     * {@code 창작희곡공모 대상 수상작, 역행기}(연극), {@code 창작산실 대본공모 낭독공연}이 실제
     * 공연이었고, "교육"은 10건 중 {@code 브런치 콘서트 아트리움 … 청년교육단원}이 실제 콘서트였다.
     * 둘 다 넣었으면 진짜 공연이 조용히 사라졌을 것이다. 아래 세 구절은 현재 데이터에서
     * 모집 공고 5건을 전부 잡으면서 오탐이 하나도 없다.
     *
     * <p>제목으로 거르는 건 본질적으로 무딘 방법이라 <b>정확도 쪽에 맞췄다</b>. 놓치는 공고가
     * 생기더라도 진짜 공연을 지우는 것보다 낫다. 제외한 건은 로그로 남겨 확인할 수 있게 한다.
     */
    private static final List<String> NON_PERFORMANCE_MARKERS =
            List.of("참여자 모집", "참가자 모집", "서포터즈");

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher events;
    private final SeatGenerator seatGenerator;
    private final PerformanceCategoryResolver categoryResolver;
    private final ShowTimeDistributionProperties showTimes;
    private final PerformanceRemover performanceRemover;

    /** 한 페이지 분량 동기화 결과. skipped(이미 존재해 갱신만 함)는 received - created 로 구한다. */
    public record SyncBatchResult(int received, int created) {}

    @Transactional
    public SyncBatchResult sync(List<ExternalPerformance> externals) {
        int saved = 0;
        LocalDate today = LocalDate.now();

        for (ExternalPerformance external : externals) {
            // 모집 공고 등 예매 대상이 아닌 항목. 규칙이 생기기 전에 이미 들어온 건도 여기서 걷어낸다.
            if (isNotPerformance(external.getTitle())) {
                continue;
            }
            // 이미 종료된 공연은 화면에 노출되지 않으므로 회차/좌석까지 만들 필요가 없다.
            if (external.getEndDate().isBefore(today)) {
                continue;
            }
            if (ChronoUnit.DAYS.between(external.getStartDate(), external.getEndDate()) > MAX_RUN_DAYS) {
                continue;
            }

            Performance performance = performanceRepository.findByExternalId(external.getExternalId())
                    .orElse(null);

            if (performance == null) {
                // 다른 소스로 이미 들어온 같은 공연 — 더 풍부한(같거나 높은 순위) 쪽이 있으면 새로 만들지
                // 않고, 비어 있는 주소·좌표만 보태 준다. 낮은 순위 쪽만 있으면 새로 만들고, 낮은 쪽은
                // 배치 끝의 mergeCrossSourceDuplicates 가 정리한다.
                Performance better = findBetterDuplicate(external);
                if (better != null) {
                    better.fillMissingLocation(external.getAddress(), external.getLatitude(), external.getLongitude());
                    continue;
                }
                performance = performanceRepository.save(toEntity(external));
                createSchedules(performance, external);
                saved++;
                // 저장한 검색과의 매칭은 "새로 나타난 공연"에만 반응한다 — 이미 있던 공연이
                // 회차만 보충되는 topUpSchedules 경로는 안 걸린다(PerformanceRegisteredEvent 주석).
                events.publishEvent(new PerformanceRegisteredEvent(performance.getId()));
            } else {
                performance.updateFromExternal(
                        external.getTitle(), external.getGenre(), external.getVenue(),
                        external.getVenueFacilityId(), external.getVenueHallId(),
                        external.getAddress(), external.getLatitude(), external.getLongitude(),
                        external.getStartDate(), external.getEndDate(),
                        external.getPosterUrl(), external.getDescription(),
                        external.getAgeLimit(), external.getRunningTime(), external.getCastMembers(),
                        external.getTotalSeatCount(), external.getBasePrice(),
                        external.getSourceType(), categoryResolver.resolve(external.getGenre()),
                        external.getRegion(), external.getBookingLinks());
                performance.updateGuidance(external.getShowTimeGuidance(), external.getPriceGuidance());
                topUpSchedules(performance, external);
            }
        }
        log.info("공연 동기화 완료. 수신={}, 신규={}", externals.size(), saved);
        return new SyncBatchResult(externals.size(), saved);
    }

    /** 제목에 모집 공고 표지가 들어 있으면 공연이 아니다. */
    private boolean isNotPerformance(String title) {
        return title != null && NON_PERFORMANCE_MARKERS.stream().anyMatch(title::contains);
    }

    /**
     * 이 규칙이 생기기 전에 이미 수집된 모집 공고를 걷어낸다.
     *
     * <p>수집 루프의 {@code continue} 만으로는 부족하다 — 그건 "다시 내려온" 항목만 막는다.
     * 이미 DB 에 있는 건은 API 응답에서 빠지는 순간 영영 남으므로, 저장된 제목을 직접 훑는다.
     * 멱등이라 동기화 때마다 돌려도 안전하다.
     *
     * <p>예매가 걸린 항목은 남긴다. 표를 산 사람에게 예매 내역은 영수증이라, 수집 규칙이
     * 바뀌었다고 그 기록을 지울 수는 없다. 삭제는 {@link PerformanceRemover} 가 회차마다 좌석을
     * 먼저 잠그고 확인한 뒤 한다 — 트랜잭션 밖에서 불러야 하므로 여기엔 {@code @Transactional} 이 없다.
     */
    public int purgeNonPerformances() {
        List<Performance> targets = performanceRepository.findAll().stream()
                .filter(p -> isNotPerformance(p.getTitle()))
                .toList();

        int removed = 0;
        for (Performance performance : targets) {
            if (!performanceRemover.removeIfUntouched(performance.getId(), null)) {
                log.warn("공연이 아닌 항목이지만 예매·선점이 있어 남긴다. id={}, title={}",
                        performance.getId(), performance.getTitle());
                continue;
            }
            log.info("공연이 아닌 항목을 제거했다. title={}", performance.getTitle());
            removed++;
        }
        return removed;
    }

    /**
     * 다른 소스에서 들어온 <b>같은 공연</b>을 하나로 합친다 (멱등 — 매 배치 끝에 돈다).
     *
     * <p>같은 공연이 KOPIS 와 문화정보에서 따로 들어와 목록에 두 번 떴다
     * ({@code 다이브 [대학로]} / {@code [대학로] 다이브}). 수집 루프의 중복 검사는 "더 나은 쪽이
     * 먼저 있을 때"만 막는다 — 소스는 표준데이터 → 문화정보 → KOPIS 순으로 돌기 때문에 KOPIS 가
     * 나중에 들어오면 낮은 쪽이 이미 DB 에 있다. 그건 여기서 걷어낸다.
     *
     * <p>남기는 쪽은 소스 순위(KOPIS > 표준데이터 > 문화정보)가 높은 쪽이고, 지우는 쪽의 주소·좌표를
     * 비어 있으면 옮겨 받는다. <b>예매가 걸린 쪽은 지우지 않는다</b> — 표를 산 사람에게 예매 내역은
     * 영수증이다(purgeNonPerformances 와 같은 원칙). 같은 소스 안의 중복은 건드리지 않는다
     * — 기관이 일부러 나눠 올린 상품(일반/회원 패키지 등)일 수 있다.
     *
     * <p>삭제는 {@link PerformanceRemover} 가 회차마다 좌석을 먼저 잠그고 확인한 뒤 한다. 선점·빈자리
     * 알림이 걸린 쪽도 남긴다. 트랜잭션 밖에서 불러야 하므로 여기엔 {@code @Transactional} 이 없다.
     */
    public int mergeCrossSourceDuplicates() {
        List<Performance> live = performanceRepository.findByEndDateGreaterThanEqual(LocalDate.now()).stream()
                .filter(p -> ShowIdentity.rank(p.getSourceType()) > 0)
                .toList();

        // 같은 공연 후보끼리만 비교한다. 정규화한 제목이 같은 묶음(기본 규칙)과, 시작일·종료일이
        // 정확히 같은 묶음(느슨한 규칙 — ShowIdentity.looselySame)을 따로 만든다.
        java.util.Map<String, List<Performance>> candidates = new java.util.HashMap<>();
        for (Performance p : live) {
            String key = ShowIdentity.titleKey(p.getTitle());
            if (!key.isEmpty()) candidates.computeIfAbsent("T|" + key, k -> new java.util.ArrayList<>()).add(p);
            if (p.getStartDate() != null && p.getEndDate() != null) {
                candidates.computeIfAbsent("D|" + p.getStartDate() + "|" + p.getEndDate(),
                        k -> new java.util.ArrayList<>()).add(p);
            }
        }

        int removed = 0;
        // 한 공연이 두 묶음에 다 들 수 있으므로 지운 공연은 묶음과 상관없이 기억한다.
        java.util.Set<Long> gone = new java.util.HashSet<>();
        for (List<Performance> group : candidates.values()) {
            if (group.size() < 2) continue;
            // 순위 높은 것 → 먼저 들어온 것(id 작은 것) 순. 앞에서부터 남길 후보가 된다.
            List<Performance> sorted = group.stream()
                    .sorted(java.util.Comparator.comparingInt((Performance p) -> -ShowIdentity.rank(p.getSourceType()))
                            .thenComparing(Performance::getId))
                    .toList();
            for (Performance keeper : sorted) {
                if (gone.contains(keeper.getId())) continue;
                ShowIdentity keep = identityOf(keeper);
                for (Performance other : sorted) {
                    if (other == keeper || gone.contains(other.getId())) continue;
                    if (other.getSourceType() == keeper.getSourceType()) continue;
                    if (ShowIdentity.rank(other.getSourceType()) > ShowIdentity.rank(keeper.getSourceType())) continue;
                    if (!keep.sameShowAs(identityOf(other))) continue;
                    if (!performanceRemover.removeIfUntouched(other.getId(), keeper.getId())) {
                        log.warn("다른 소스의 같은 공연이지만 예매·선점이 있어 남긴다. keep={}, dup={}, title={}",
                                keeper.getId(), other.getId(), other.getTitle());
                        continue;
                    }
                    gone.add(other.getId());
                    log.info("다른 소스의 같은 공연을 합쳤다. keep={}({}), removed={}({}), title={}",
                            keeper.getId(), keeper.getSourceType(), other.getId(), other.getSourceType(), other.getTitle());
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 이번에 들어온 항목과 같은 공연이 다른 소스로 이미 있고, 그쪽이 같거나 더 풍부하면 그것 */
    private Performance findBetterDuplicate(ExternalPerformance e) {
        if (e.getStartDate() == null || e.getEndDate() == null) return null;
        ShowIdentity incoming = ShowIdentity.of(e.getTitle(), e.getVenue(), e.getStartDate(), e.getEndDate());
        if (incoming.titleKey().isEmpty()) return null;
        int incomingRank = ShowIdentity.rank(e.getSourceType());
        for (Performance p : performanceRepository.findOverlapping(e.getStartDate(), e.getEndDate())) {
            if (p.getSourceType() == e.getSourceType()) continue;
            if (ShowIdentity.rank(p.getSourceType()) < incomingRank || ShowIdentity.rank(p.getSourceType()) == 0) continue;
            if (identityOf(p).sameShowAs(incoming)) return p;
        }
        return null;
    }

    private static ShowIdentity identityOf(Performance p) {
        return ShowIdentity.of(p.getTitle(), p.getVenue(), p.getStartDate(), p.getEndDate());
    }

    /**
     * 외부 응답과 무관하게 로컬 DB만 보고 회차를 보충한다. {@link #topUpSchedules} 는 그
     * 공연이 이번 동기화 응답에 들어와야만 호출된다 — KOPIS 증분 수집은 원본이 바뀐 건만
     * 다시 내려오므로, 한 번 등록된 뒤로 원본이 안 바뀐 공연은 몇 달을 더 하더라도 응답에
     * 다시 잡히지 않고, 그러면 topUpSchedules 도 영영 호출되지 않는다.
     *
     * <p>이 메서드가 그 사각지대를 잡는 마지막 안전망이다 — 매 배치(PerformanceSyncScheduler)
     * 끝에서 외부 호출 성공 여부와 무관하게 돈다. 원본의 실제 회차 시각(dtguidance)은 다시
     * 받아오지 않으므로, 이 경로로 늘어나는 구간은 원본에 회차 개념이 없을 때와 같은 대체
     * 규칙(실측 시각 분포)을 쓴다 — 처음 며칠은 KOPIS 실측 시각인데 그 뒤로는 대체 시각으로
     * 바뀌는 공연이 있을 수 있다는 뜻이다. 예매 가능 여부가 먼저고, 시각 정확도는 그다음이다.
     */
    @Transactional
    public int topUpStaleSchedules() {
        LocalDate today = LocalDate.now();
        List<Long> staleIds = performanceRepository.findIdsWithNoFutureSchedule(today, LocalDateTime.now());

        int topped = 0;
        for (Long id : staleIds) {
            Performance performance = performanceRepository.findById(id).orElse(null);
            if (performance == null) {
                continue;
            }
            LocalDate lastScheduled = scheduleRepository.findMaxShowAtByPerformanceId(id)
                    .map(LocalDateTime::toLocalDate)
                    .orElse(null);
            LocalDate from = lastScheduled == null
                    ? laterOf(performance.getStartDate(), today)
                    : laterOf(lastScheduled.plusDays(1), today);
            if (from.isAfter(performance.getEndDate())) {
                continue;
            }

            int totalSeats = performance.getTotalSeatCount() == null ? 200 : performance.getTotalSeatCount();
            List<PerformanceSchedule> created = createDefaultSchedules(performance, from, totalSeats);
            if (created.isEmpty()) {
                continue;
            }
            performanceRepository.flush();
            for (PerformanceSchedule schedule : created) {
                seatGenerator.generate(schedule.getId(), performance.getVenueHallId(), performance.getVenue(),
                        performance.getTotalSeatCount(), performance.getBasePrice(), null);
            }
            // 여기 오는 공연은 선정 조건상 예매 가능한 회차가 0 개였다(findIdsWithNoFutureSchedule).
            // 증분 수집이 다시 안 다루는 공연이 대부분이라, 실제 0 → N 전이는 topUpSchedules 보다
            // 이 경로에서 훨씬 자주 일어난다 — 알림을 여기 안 걸면 기능이 거의 안 돈다.
            publishIfNewlyBookable(id, false);
            topped++;
        }
        if (topped > 0) {
            log.info("증분 수집이 다시 다루지 않은 공연의 회차를 보충했다. {}건", topped);
        }
        return topped;
    }

    private Performance toEntity(ExternalPerformance e) {
        Performance performance = Performance.builder()
                .externalId(e.getExternalId())
                .sourceType(e.getSourceType())
                .title(e.getTitle())
                .genre(e.getGenre())
                .category(categoryResolver.resolve(e.getGenre()))
                .venue(e.getVenue())
                .venueFacilityId(e.getVenueFacilityId())
                .venueHallId(e.getVenueHallId())
                .address(e.getAddress())
                .region(e.getRegion())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .posterUrl(e.getPosterUrl())
                .description(e.getDescription())
                .ageLimit(e.getAgeLimit())
                .runningTime(e.getRunningTime())
                .castMembers(e.getCastMembers())
                .totalSeatCount(e.getTotalSeatCount())
                .basePrice(e.getBasePrice())
                .bookingLinks(e.getBookingLinks())
                .build();
        performance.updateGuidance(e.getShowTimeGuidance(), e.getPriceGuidance());
        return performance;
    }

    /** 신규 수집. 오늘(또는 시작일, 더 늦은 쪽)부터 종료일까지 한 번에 다 만든다. */
    private void createSchedules(Performance performance, ExternalPerformance external) {
        LocalDate from = laterOf(performance.getStartDate(), LocalDate.now());
        generateSchedulesFrom(performance, external, from);
    }

    /**
     * 회차를 보충하고, <b>없던 예매 가능 회차가 생겼으면</b> 알림 이벤트를 발행한다.
     * 판정 규칙은 {@link #hasBookable} 참고.
     */
    private void topUpSchedules(Performance performance, ExternalPerformance external) {
        boolean hadBookable = hasBookable(performance.getId());
        topUpSchedulesInternal(performance, external);
        publishIfNewlyBookable(performance.getId(), hadBookable);
    }

    /**
     * 예매 가능한(아직 안 지난) 회차가 하나라도 있는지.
     *
     * <p>알림은 이 값이 <b>false 였다가 true 가 될 때만</b> 보낸다. 이미 예매할 수 있는
     * 공연은 사용자가 언제든 들어가서 고를 수 있으므로 알림이 소음이 된다. 실제로 개발계
     * 카탈로그의 74.9% 가 예매 가능한 회차 0 개였던 적이 있어(README 참고) 이 전이가
     * 드물지 않다.
     *
     * <p>"회차를 새로 만들었는가"로 판정하지 않는 이유: 만든 회차가 이미 지난 시각일 수 있다
     * (회차 생성이 19시 고정이라, 오늘 저녁에 도는 배치가 만든 오늘자 회차는 과거다).
     * 그러면 사용자는 알림을 받고 들어와서 살 게 없는 화면을 본다.
     */
    private boolean hasBookable(Long performanceId) {
        return scheduleRepository.existsByPerformanceIdAndShowAtAfter(performanceId, LocalDateTime.now());
    }

    /**
     * 없던 예매 가능 회차가 생겼으면 알림 이벤트를 발행한다.
     *
     * <p>이벤트는 이 트랜잭션 안에서 발행되지만 받는 쪽은 커밋 후에만 돈다 —
     * 이유는 {@link ScheduleOpenedEvent} 주석 참고.
     */
    private void publishIfNewlyBookable(Long performanceId, boolean hadBookable) {
        if (!hadBookable && hasBookable(performanceId)) {
            events.publishEvent(new ScheduleOpenedEvent(performanceId));
        }
    }

    /**
     * 이미 있는 공연의 회차를 채운다. 첫 수집 때 종료일까지 다 만들어 두므로 보통은 할 일이
     * 없지만, 그 사이 원본의 공연 기간(endDate)이 늘어난 경우 마지막 회차 다음 날부터
     * 새 종료일까지의 빈 구간만 추가한다.
     *
     * <p>마지막 회차 "다음 날"부터 시작해서, 이미 회차가 있는 날은 절대 다시 건드리지
     * 않는다 — (performance_id, showAt) 유니크 제약과도, 과거/판매 중인 회차를 보존해야
     * 한다는 규칙과도 맞는다.
     */
    private void topUpSchedulesInternal(Performance performance, ExternalPerformance external) {
        // 공연의 전체 회차 컬렉션을 로딩해 스트림으로 max 를 구하면 회차가 쌓일수록(최대 90개)
        // 매 동기화·공연마다 그 행 수만큼 읽는 꼴이 된다. 집계 쿼리 하나로 대신한다.
        LocalDate lastScheduled = scheduleRepository.findMaxShowAtByPerformanceId(performance.getId())
                .map(LocalDateTime::toLocalDate)
                .orElse(null);

        LocalDate from = lastScheduled == null
                ? laterOf(performance.getStartDate(), LocalDate.now())
                : laterOf(lastScheduled.plusDays(1), LocalDate.now());

        if (from.isAfter(performance.getEndDate())) {
            return;
        }
        generateSchedulesFrom(performance, external, from);
    }

    private static LocalDate laterOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private void generateSchedulesFrom(Performance performance, ExternalPerformance external, LocalDate from) {
        Map<DayOfWeek, List<LocalTime>> showTimesByDay = external.getShowTimesByDay();
        int totalSeats = performance.getTotalSeatCount() == null ? 200 : performance.getTotalSeatCount();

        List<PerformanceSchedule> created = (showTimesByDay == null || showTimesByDay.isEmpty())
                ? createDefaultSchedules(performance, from, totalSeats)
                : createGuidedSchedules(performance, showTimesByDay, from, totalSeats);

        if (created.isEmpty() && showTimesByDay != null && !showTimesByDay.isEmpty()) {
            // dtguidance 가 있었지만 남은 기간에 매칭되는 요일이 하나도 없는 경우 등 (안전망)
            created = createDefaultSchedules(performance, from, totalSeats);
        }
        if (created.isEmpty()) {
            return;
        }

        performanceRepository.flush();

        // 이번에 새로 만든 회차만 좌석을 만든다 — 이미 있던 회차까지 매번 다시 훑으면(SeatGenerator
        // 가 내부에서 건너뛰긴 해도 그 판단 자체가 조회 하나다) 동기화할 때마다 공연 하나당
        // "누적된 전체 회차 수"만큼 쓸모없는 조회가 늘어난다 — 보충이 반복될수록 매 동기화가 느려진다.
        for (PerformanceSchedule schedule : created) {
            seatGenerator.generate(schedule.getId(), performance.getVenueHallId(), performance.getVenue(),
                    performance.getTotalSeatCount(), performance.getBasePrice(), external.getPricesByGrade());
        }
    }

    /**
     * 원본에 회차 개념이 없을 때 쓰는 대체 규칙: from 부터 종료일까지 하루 한 회차.
     *
     * 시각은 KOPIS 시간대별 상연 통계의 실측 분포를 따른다(showtime-distribution.yml).
     * 설정이 없으면 예전 규칙인 19:00 고정으로 떨어진다.
     */
    private List<PerformanceSchedule> createDefaultSchedules(Performance performance, LocalDate from, int totalSeats) {
        LocalDate cursor = from;
        LocalDate end = performance.getEndDate();
        List<PerformanceSchedule> created = new ArrayList<>();

        while (!cursor.isAfter(end)) {
            created.add(addSchedule(performance, LocalDateTime.of(cursor, resolveShowTime(performance, cursor)), totalSeats));
            cursor = cursor.plusDays(1);
        }
        return created;
    }

    /**
     * 공연과 날짜로 seed 를 만들어 분포에서 시각을 고른다.
     * 같은 공연의 같은 날짜면 항상 같은 시각이 나오므로, 배치를 다시 돌려도 결과가 흔들리지 않는다.
     */
    private LocalTime resolveShowTime(Performance performance, LocalDate date) {
        long seed = 31L * performance.getExternalId().hashCode() + date.toEpochDay();
        LocalTime picked = showTimes.pick(seed);
        return picked != null ? picked : SHOW_TIME;
    }

    /** KOPIS dtguidance 파싱 결과(요일별 실제 공연시간)로 회차를 만든다. 하루에 여러 회차(마티네/저녁)도 반영. */
    private List<PerformanceSchedule> createGuidedSchedules(Performance performance, Map<DayOfWeek, List<LocalTime>> showTimesByDay,
                                       LocalDate from, int totalSeats) {
        LocalDate cursor = from;
        LocalDate end = performance.getEndDate();
        List<PerformanceSchedule> created = new ArrayList<>();

        while (!cursor.isAfter(end)) {
            List<LocalTime> times = showTimesByDay.get(cursor.getDayOfWeek());
            if (times != null) {
                for (LocalTime time : times) {
                    created.add(addSchedule(performance, LocalDateTime.of(cursor, time), totalSeats));
                }
            }
            cursor = cursor.plusDays(1);
        }
        return created;
    }

    private PerformanceSchedule addSchedule(Performance performance, LocalDateTime showAt, int totalSeats) {
        PerformanceSchedule schedule = PerformanceSchedule.builder()
                .showAt(showAt)
                .totalSeats(totalSeats)
                .remainingSeats(totalSeats)
                .build();
        performance.addSchedule(schedule);
        return schedule;
    }
}
