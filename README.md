# 객석 — 공공데이터 기반 공연 예매 시스템

공공데이터포털의 공연 정보를 배치로 수집해 좌석·회차 도메인을 자체 생성하고,
**한 좌석에 대한 동시 예매 경쟁을 4가지 전략으로 제어·비교**하는 예매 시스템.

> 공연 메타데이터(공연명·공연장·기간·객석수·요금·좌표)는 공공데이터 API 연동,
> 회차/좌석/예매 도메인은 자체 설계입니다. 실제 예매처와는 무관합니다.

---

## 1. 기술 스택

| 구분 | 사용 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| ORM | JPA(Hibernate) — 정적 CRUD / MyBatis 3.0.3 — 동적·집계 쿼리 |
| DB | MariaDB (운영), H2 (로컬/테스트) |
| View | Thymeleaf |
| Build | Gradle |
| 외부 연동 | 공공데이터포털 Open API, (예정) 토스페이먼츠 |

---

## 2. 아키텍처

```
[공공데이터포털]
      │  매일 04:00 배치 (PerformanceSyncScheduler)
      ▼
PublicPerformanceClient ─┐
CulturePerformanceClient ─┤─► ExternalPerformance(정규화) ─► PerformanceSyncService
                          │                                        │ upsert
                          └──────── PublicDataParser               ▼
                                    (필드/날짜/요금 정제)      Performance
                                                                   │ 회차 생성
                                                                   ▼
                                                          PerformanceSchedule
                                                                   │ 객석수 기준
                                                                   ▼
                                                              SeatGenerator
                                                                   │
                                                                   ▼
                                                                 Seat
                                                                   │
                              ReservationFacade ──► ReservationService ──► Reservation
                                (전략 선택/재시도)      (락 제어)
                                                                   ▲
                                                          HoldExpireScheduler
                                                          (30초마다 만료 선점 해제)
```

---

## 3. ERD

```
┌──────────────────┐
│   performance    │  공공데이터 수집 원본
├──────────────────┤
│ PK id            │
│ UK external_id   │◄── 재수집 시 중복 방지 키
│    title         │
│    venue/address │
│    latitude/lng  │
│    start/endDate │
│    posterUrl     │
│    totalSeatCount│──┐ 좌석 생성 기준
│    basePrice     │──┤ 등급별 가격 기준
└────────┬─────────┘  │
         │ 1:N        │
┌────────▼──────────┐ │
│performance_schedule│ │
├───────────────────┤ │
│ PK id             │ │
│ FK performance_id │ │
│    showAt         │ │  UK(performance_id, showAt)
│    totalSeats     │◄┘
│    remainingSeats │
│    version        │  ← 낙관적 락 (잔여석 갱신 손실 방지)
└────────┬──────────┘
         │ 1:N
┌────────▼──────────┐        ┌──────────────────┐
│      seat         │        │    seat_hold     │
├───────────────────┤        ├──────────────────┤
│ PK id             │◄───────│ PK seat_id       │ ← PK 충돌로 중복 선점 차단
│ FK schedule_id    │  1:0..1│    memberId      │ ← member.id 값 (FK 아님)
│    section        │        │    expiresAt     │
│    seatNo         │        └──────────────────┘
│    grade          │  UK(schedule_id, section, seatNo)
│    status         │  AVAILABLE / HELD / SOLD
│    price          │
│    version        │  ← 낙관적 락
└────────┬──────────┘
         │ 1:N
┌────────▼──────────┐
│   reservation     │
├───────────────────┤
│ PK id             │
│ UK reservationNo  │
│    memberId       │ ← member.id 값 (FK 아님)
│ FK seat_id        │
│    status         │  PENDING / CONFIRMED / CANCELED / EXPIRED
│    amount         │
│    createdAt      │
│    holdExpiresAt  │  ← 결제 마감 시각 (기본 10분)
└───────────────────┘

┌──────────────────┐
│      member      │  회원 (Spring Security 인증 주체)
├──────────────────┤
│ PK id            │
│ UK loginId       │
│    password      │  BCrypt 인코딩
│    nickname      │
│    role          │  USER / ADMIN
│    createdAt     │
└──────────────────┘
  reservation.memberId / seat_hold.memberId 와 FK 로 연결하지 않는다.
  ReservationConcurrencyTest 가 실제 회원 로우 없이 임의 memberId 로
  스레드 100개를 돌리는데, FK 제약을 걸면 이 테스트가 깨지기 때문.
```

---

## 4. 핵심: 동시성 제어 4종 비교

좌석 1석에 **스레드 100개가 동시에** 예매를 시도하는 테스트
(`ReservationConcurrencyTest`)를 전략별로 실행합니다.

| 전략 | 구현 | 기대 결과 |
|---|---|---|
| `NONE` | 락 없음. 조회 → 검사 → 갱신 | **성공 2건 이상 (오버부킹 발생)** |
| `PESSIMISTIC` | `@Lock(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE` | 성공 1건 |
| `OPTIMISTIC` | `@Version` + 3회 재시도(백오프) | 성공 1건 (충돌 재시도 발생) |
| `UNIQUE` | `seat_hold` PK 충돌 → `DataIntegrityViolationException` | 성공 1건 |

실행:

```bash
./gradlew test --tests '*ReservationConcurrencyTest*'
```

콘솔에 아래 형태로 출력되므로 그대로 표로 옮기면 됩니다.

```
| PESSIMISTIC | 성공   1 | 실패  99 | 실제 예매행   1 |   xxx ms |
```

### 설계 판단

- **실서비스 경로는 비관적 락.** 좌석은 경쟁이 확실하게 발생하는 자원이라
  낙관적 락의 재시도 비용이 오히려 크다.
- **락 타임아웃 3초 필수.** 타임아웃 없이 `FOR UPDATE` 를 걸면 오픈런 때
  커넥션 풀이 통째로 묶인다. (`@QueryHints`)
- **재시도는 트랜잭션 밖에서.** 낙관적 락 재시도를 `@Transactional` 메서드
  안에서 돌리면 이미 롤백 마킹된 트랜잭션에서 재시도하게 되므로
  `ReservationFacade` 로 분리했다.
- **잔여석 카운터는 별도 락.** `Seat` 만 잠그면 `PerformanceSchedule.remainingSeats`
  갱신이 유실된다. 회차도 `findByIdForUpdate` 로 잠근다.

---

## 5. 공공데이터 연동에서 겪은 문제

| 문제 | 해결 |
|---|---|
| 기관마다 필드명이 다름 (`eventNm` / `공연행사명` / `title`) | `PublicDataParser.text()` 가 후보 키를 순서대로 탐색 |
| 날짜 포맷 4종 혼재 (`yyyy-MM-dd`, `yyyyMMdd`, `yyyy.MM.dd` …) | 포맷 배열을 순회하며 파싱, 전부 실패 시 `null` 반환 후 스킵 |
| 관람요금이 `"전석 30,000원"` 같은 문자열 | 정규식으로 첫 금액 추출, `"무료"` 는 0 |
| 인증키 오류·트래픽 초과 시 JSON 이 아닌 XML 응답 | 응답 첫 글자가 `<` 면 경고 로그 후 빈 리스트 반환 |
| `serviceKey` 재인코딩으로 401 | `UriComponentsBuilder.build(true)` 로 인코딩 억제 |
| 개발계정 트래픽 제한(일 1,000건) | 실시간 호출 대신 **일 1회 배치 수집 + 로컬 적재** |
| 원본에 고유키가 없어 재수집 시 중복 적재 | `공연명 + 장소 + 시작일` 해시로 대체키 생성, `external_id` UK |
| 원본에 "회차" 개념 없음 | 공연 기간 내 최대 8회차(19:00) 자동 생성 규칙 문서화 |

---

## 6. JPA / MyBatis 역할 분리

- **JPA**: 엔티티 CRUD, 락 제어(`@Lock`, `@Version`), 연관관계 탐색
- **MyBatis**: 좌석 배치도 단건 조회(`selectSeatMap`), 일자별 매출 집계
  (`selectDailySales` — 동적 `<if>` 조건 + `GROUP BY`)

좌석 배치도를 JPA 로 풀면 회차→좌석 N+1 이 나고, 매출 집계는 JPQL 로
표현하기 어려워 MyBatis 로 분리했습니다.

---

## 7. 실행 방법

```bash
# 1) 공공데이터포털에서 활용신청 후 인증키 발급
#    - 전국공연행사정보표준데이터
#    - 한눈에보는문화정보조회서비스

# 2) 환경변수로 주입 (application.yml 에 직접 넣지 말 것)
export PUBLICDATA_SERVICE_KEY="발급받은_인코딩된_키"

# 3) 실행 (H2 인메모리)
./gradlew bootRun

# 4) 공공데이터 수동 수집
curl -X POST http://localhost:8080/api/admin/sync

# 5) 접속
open http://localhost:8080
```

H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:ticket`)

---

## 8. API

| Method | URL | 설명 |
|---|---|---|
| GET | `/` | 공연 목록 |
| GET | `/performances/{id}` | 공연 상세 + 회차 |
| GET | `/schedules/{id}/seats` | 좌석 배치도 |
| POST | `/api/seats/{seatId}/hold?memberId=&strategy=` | 좌석 선점 (전략 선택 가능) |
| POST | `/api/reservations/{no}/confirm` | 결제 확정 |
| POST | `/api/reservations/{no}/cancel?memberId=` | 취소 + 환불액 계산 |
| POST | `/api/admin/sync` | 공공데이터 수동 수집 |

환불 수수료: 10일 전 0% / 7일 전 10% / 3일 전 20% / 1일 전 30% / 당일 취소 불가

---

## 9. 남은 작업

- [x] Spring Security + 회원 도메인 (세션 기반 폼 로그인, `memberId` 파라미터 제거)
- [ ] 토스페이먼츠 테스트 결제 연동 + 웹훅으로 결제 상태 동기화
- [ ] 관리자 대시보드 (`selectDailySales` + Chart.js)
- [ ] 마이페이지 예매 내역 / 취소 화면
- [ ] Docker + GitHub Actions CI
- [ ] AWS EC2 + RDS 배포
