<img src="docs/logo.svg" alt="객석" height="44">

# 객석 — 공공데이터 기반 공연 예매 시스템

[![CI](https://github.com/HurKyungsoo/ticketing/actions/workflows/ci.yml/badge.svg)](https://github.com/HurKyungsoo/ticketing/actions/workflows/ci.yml)

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
| 배포 | Docker / Docker Compose (앱 + MariaDB), GitHub Actions CI |
| 외부 연동 | 공공데이터포털 Open API(공연/문화정보), KOPIS(공연예술통합전산망), 토스페이먼츠 |

---

## 2. 아키텍처

```
[공공데이터포털]
      │  매일 04:00 배치 (PerformanceSyncScheduler)
      │  소스 하나가 실패해도 나머지 소스는 계속 진행
      ▼
PublicPerformanceClient ──┐
CulturePerformanceClient ─┤─► ExternalPerformance(정규화) ─► PerformanceSyncService
KopisPerformanceClient ───┤    (showTimesByDay/pricesByGrade                │ upsert
                          │     는 KOPIS 만 채움)                            ▼ (종료/상설전시류 제외)
                          └──────── PublicDataParser               Performance
                                    (필드/날짜/요금 정제)                │ 회차 생성 (dtguidance 있으면
                                                                          │  실제 요일별 시간, 없으면
                                                                          ▼  8일 · 실측 시각 분포)
                                                          PerformanceSchedule
                                                                   │ 공연장명 매칭
                                                                   ▼
                                                              SeatGenerator ◄── venue-layouts.yml
                                                            (등급별 실제가격        (VenueLayoutProperties)
                                                             있으면 그대로, 없으면
                                                             basePrice 비율 계산)
                                                                   │
                                                                   ▼
                                                                 Seat
                                                                   │
                              ReservationFacade ──► ReservationService ──► Reservation
                                (전략 선택/재시도)      (락 제어)                │
                                                                   ▲            │ 확정/취소 시
                                                          HoldExpireScheduler    ▼
                                                          (30초마다 만료 선점 해제)  ExternalInventoryClient
                                                                              (Mock — 실제 파트너 없음)
```

`venue-layouts.yml` 에 공연장명과 실제 구역(층)별 행·열·등급 구조를 정의해두면,
공연장명이 정확히 일치하는 회차는 그 구조로 좌석이 생성된다. 일치하지 않으면
기존처럼 20석 x N줄을 비율(VIP 15% / R 25% / S 35% / A 25%)로 나눈다.
포함된 "예시극장" 항목은 실존 극장의 검증된 좌석도가 아니라 구조 예시이므로,
실제 극장 정보를 알고 있다면 교체해서 쓸 것.

`ExternalInventoryClient` 는 실제 박스오피스 파트너와의 실시간 재고 연동 포트다.
접근 가능한 실제 파트너 API가 없어 `MockExternalInventoryClient` (로그만 남김)만
있다 — 예매 확정/취소 시 호출되는 지점만 만들어뒀고, 실패해도 로컬 예매는
막지 않는다. 실제 파트너가 생기면 구현체만 교체하면 된다.

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

## 4. 핵심: 동시성 제어 5종 비교

좌석 1석에 **스레드 100개가 동시에** 예매를 시도하는 테스트
(`ReservationConcurrencyTest`)를 전략별로 실행합니다.

| 전략 | 조정자 | 구현 | 기대 결과 |
|---|---|---|---|
| `NONE` | 없음 | 락 없음. 조회 → 검사 → 갱신 | **성공 2건 이상 (오버부킹 발생)** |
| `PESSIMISTIC` | DB | `@Lock(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE` | 성공 1건 |
| `OPTIMISTIC` | DB | `@Version` + 3회 재시도(백오프) | 성공 1건 (충돌 재시도 발생) |
| `UNIQUE` | DB | `seat_hold` PK 충돌 → `DataIntegrityViolationException` | 성공 1건 |
| `DISTRIBUTED` | Redis | `SET NX PX` + Lua CAS 해제 | 성공 1건 |

실행:

```bash
./gradlew test --tests '*ReservationConcurrencyTest*'
```

실측 (로컬, H2, 100스레드/1좌석, 2회 실행):

```
| NONE        | 성공   1 | 실패  99 | 실제 예매행   1 |  139 / 198 ms |
| PESSIMISTIC | 성공   1 | 실패  99 | 실제 예매행   1 |   63 /  85 ms |
| OPTIMISTIC  | 성공   1 | 실패  99 | 실제 예매행   1 |  100 / 119 ms |
| UNIQUE      | 성공   1 | 실패  99 | 실제 예매행   1 |   96 / 140 ms |
| DISTRIBUTED | 성공   1 | 실패  99 | 실제 예매행   1 |  166 / 189 ms |
```

> `NONE` 이 성공 1건으로 나온 것은 오버부킹이 "안 나는" 것이 아니라, H2 +
> 단일 머신에서는 조회~갱신 사이 간격이 짧아 재현이 잘 안 되기 때문입니다.
> 테스트는 이 경우를 `>= 1` 로 느슨하게 검증합니다.

### 왜 분산 락(DISTRIBUTED)인가

앞의 네 전략은 구현이 달라도 **조정자가 전부 하나의 DB** 입니다. 그래서
앱 서버를 늘리는 순간 두 가지가 문제가 됩니다.

- **JVM 락은 아예 못 씁니다.** `synchronized` / `ReentrantLock` 은 프로세스
  안에서만 유효해서, 서버 3대면 3명이 같은 좌석을 동시에 잡습니다.
- **DB 가 병목이 됩니다.** 비관적 락으로 버티면 오픈런 때 경쟁에서 진 요청까지
  전부 DB 커넥션을 쥔 채 줄을 섭니다. 커넥션 풀이 마르면 좌석과 무관한
  조회 요청까지 같이 죽습니다.

`DISTRIBUTED` 는 그 조정자를 DB 밖으로 뺍니다.

```
SET seat:lock:{seatId} {token} NX PX 3000
```

- `NX` — 키가 없을 때만 세팅. "검사 후 설정"이 한 명령으로 원자 처리되므로
  `GET` → 없으면 `SET` 같은 2단계 구현에서 생기는 경쟁이 없습니다.
- `PX`(TTL) — **필수**입니다. DB 락은 커넥션이 끊기면 자동으로 풀리지만
  Redis 키는 안 그래서, 락을 쥔 서버가 죽으면 TTL 만이 유일한 안전장치입니다.
- 해제는 **Lua 스크립트로 "내 토큰일 때만 삭제"**. 단순 `DEL` 로 지우면 내 작업이
  TTL 을 넘겨 락이 이미 만료되고 다른 요청이 같은 키를 새로 잡은 상태에서
  **남의 락을 지워버릴 수 있습니다.** `GET` 후 `DEL` 하는 2단계도 그 사이에 만료가
  끼어들 수 있어, Redis 가 단일 스레드로 원자 실행하는 Lua 안에서 비교와 삭제를
  함께 합니다.

**트레이드오프 (측정으로 확인됨)**

- **단일 서버에서는 오히려 느립니다.** 위 실측에서 `DISTRIBUTED`(166/189ms)가
  `PESSIMISTIC`(63/85ms)보다 2배 이상 느립니다. 네트워크 왕복이 추가되기
  때문이며, 분산 락은 *속도*가 아니라 *확장성*을 사는 선택입니다.
- **단일 Redis 인스턴스 락은 엄밀히 안전하지 않습니다.** 마스터가 락을 내주고
  복제 전에 죽으면, 승격된 replica 에는 그 키가 없어 두 클라이언트가 같은 락을
  쥡니다. 이를 풀려는 Redlock 에 대해서도 "GC 멈춤 앞에서는 여전히 안전하지 않다"는
  Kleppmann 의 반박이 있습니다.
- 그래서 이 프로젝트는 **최종 방어선을 DB 에 그대로 남겨뒀습니다.** Redis 락을
  통과해도 `Seat.hold()` 가 `isAvailable()` 을 다시 검사하고, 좌석 유니크 제약도
  유지됩니다. Redis 는 "빠른 1차 필터", DB 는 "최종 정합성" 역할입니다.

**Redis 없이도 앱은 기동됩니다.** 커넥션이 지연 생성이라 `DISTRIBUTED` 전략만
사용할 수 없고 나머지는 그대로 동작합니다. 로컬에서는 `docker compose up redis`,
테스트는 `embedded-redis` 로 테스트 프로세스가 직접 redis-server 를 띄웁니다
(Docker 없이도 5전략을 같은 조건에서 비교하기 위해).

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

### 트러블슈팅

| 문제 | 해결 |
|---|---|
| 결제창에서 10분(선점 만료)을 넘기면 `HoldExpireScheduler` 가 좌석을 `AVAILABLE` 로 되돌리는데, 그 사이 토스 승인이 끝나 "돈은 나갔는데 좌석은 없는" 상태 발생 | 컨트롤러가 토스 승인 호출 **전**에 만료 여부를 조기 검사 + `confirmPayment`/스케줄러 양쪽에서 `Seat` 를 `findByIdForUpdate` 로 잠가 경쟁을 없앰(락 순서 Seat → PerformanceSchedule) + 그래도 승인 후 확정 실패 시 토스 결제취소 API로 자동 환불 |
| 다중 좌석 예매를 도입하며 FK 를 `reservation.seat_id` → `seat.reservation_id` 로 뒤집었더니, 좌석이 예매를 참조하는 상태에서 예매를 먼저 지우는 `ReservationConcurrencyTest` 의 `@BeforeEach` 가 제약 위반으로 터져 4전략 중 3개가 실패 (첫 케이스만 DB 가 비어 있어 통과해 원인이 가려짐) | 테스트 정리 순서를 FK 방향에 맞게 `seat → reservation` 으로 교체. 운영 경로는 예매를 삭제하지 않고 상태만 바꾸며 `Seat.release()` 가 연결을 끊으므로 영향 없음 |
| 좌석을 여러 개 잠그면 두 요청이 겹치는 좌석을 서로 다른 순서로 잠가 데드락 가능 | 기존 "Seat → PerformanceSchedule" 규칙에 **"여러 Seat 사이에도 id 오름차순"** 을 추가. `sortedIds()` / `lockSeatsInOrder()` 로 강제 |
| 예매–좌석이 1:N 이 되면서 통계 쿼리가 `seat` 를 join 하면 예매 행이 좌석 수만큼 늘어나 `SUM(r.amount)` 가 중복 합산됨 | 집계를 좌석 단위 `SUM(s.price)` 로 변경 (늘어난 행 수와 일치). JPA 쪽 `join fetch` 도 `distinct` 로 행 뻥튀기를 접음 |

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
| 원본에 "회차" 개념 없음 | 공연 기간 내 최대 8회차 자동 생성. 시각은 KOPIS 시간대별 상연 통계(`boxStatsTime`) 실측 분포를 따름 (`showtime-distribution.yml`) |
| `performance-url` 을 `http://` 로 두면 api.data.go.kr 이 `https://` 로 301 리다이렉트하는데, `HttpURLConnection` 은 프로토콜이 바뀌는 리다이렉트를 안 따라가서 항상 리다이렉트 HTML만 받고 실패 | `application.yml` 의 `performance-url` 을 `https://` 로, 실제 등록된 End Point(`tn_pubr_public_pblprfr_event_info_api`)로 수정 |
| 한눈에보는문화정보 API 는 호스트가 이전됐고 정상 응답도 XML 전용인데, 옛 코드는 JSON 을 가정하고 `<` 로 시작하는 응답을 전부 오류로 간주해 조용히 폐기 → CIA- 레코드가 늘 0건 | `culture-url` 을 실제 End Point(`/B553457/cultureinfo/period2`)로 교체, `XmlMapper` 로 파싱 전환. 실제 필드명(`seq`/`title`/`place`/`realmName`/`area`/`sigungu` 등)은 data.go.kr 상세 페이지에 로그인해 Swagger Models 패널을 펼쳐 직접 확인 |
| 문화정보 응답에 박물관 "상설전시" 류가 섞여 있음 (`startDate` 가 몇 년 전, `endDate` 는 먼 미래) — `createSchedules()` 가 `startDate` 기준 연속 8일치 회차만 만들다 보니 이미 다 지난, 예매 불가능한 회차만 생성됨 | 동기화 단계에서 기간 90일 초과 항목은 상설전시류로 보고 제외. 이미 종료된 공연도 동일하게 제외 |
| 소스 하나(예: 문화정보)가 호출/파싱에 실패해도 다른 소스 동기화가 막히면 안 됨 | `PerformanceSyncScheduler` 가 소스별로 완전히 격리된 함수에서 try-catch, 실패해도 나머지 소스는 계속 진행. `/api/admin/sync` 응답도 소스별로 분리 |
| KOPIS 목록(pblprfr) 응답에는 회차시간(dtguidance)/등급별가격(pcseguidance)이 없고 상세(pblprfr/{mt20id})에만 있음 | 목록 페이지 1건당 상세를 1회 더 호출. 상세 호출/파싱이 개별 건에서 실패해도 그 건만 목록 정보로 대체하고 나머지는 계속 진행 |
| dtguidance/pcseguidance 는 자유 텍스트라 정규식으로 파싱해야 함. 실제 값(`"화요일(19:30), 금요일(19:30)"`, `"토요일(11:00,14:00)"`, `"VIP석 120,000원, R석 90,000원..."`)은 실제 키 발급 후 라이브 호출로 확인 | "요일명(시각)" / "요일명 ~ 요일명(시각)" / "매일(시각)" 패턴, 등급별로는 "등급+석+금액+원" 패턴을 추출. 패턴을 하나도 못 찾으면 `null` 반환 → 8일 대체 규칙(실측 시각 분포) / basePrice 비율 계산으로 자동 대체 |
| "화요일"의 "일" 한 글자가 요일 매칭용 단일 문자(월화수목금토**일**)와 겹쳐서 일요일로 오매칭됨 → 없는 일요일 회차가 만들어지고, 요일 범위("목요일 ~ 금요일")의 끝 요일이 단일 패턴에도 중복으로 잡혀 같은 회차를 두 번 만들려다 유니크 제약(`uk_schedule`) 위반으로 배치가 죽을 뻔함 | 반드시 "요일" 전체가 붙은 형태만 매칭하도록 정규식 수정(`(월\|화\|수\|목\|금\|토\|일)요일`), 요일별 시각을 `Set` 에 모아 중복 제거 후 리스트로 변환 |
| KOPIS 응답 `Content-Type` 이 `application/xml` 이고 charset 이 없어서, `.body(String.class)` 로 먼저 String 을 받으면 Spring 이 ISO-8859-1 로 디코딩하고 그걸 다시 `.getBytes()`(플랫폼 기본 인코딩, 한글 Windows 라 MS949)로 바이트를 만드니 두 번 깨져서 한글이 전부 깨짐 (문화정보 클라이언트도 동일 패턴이라 같은 문제) | `.body(String.class)` 대신 `.body(byte[].class)` 로 원본 바이트를 그대로 받아 `XmlMapper` 에 넘김 — XML 선언(`encoding="UTF-8"`)으로 직접 인코딩을 판별하게 해서 중간 디코딩 단계를 아예 없앰 |
| 목록의 "진행 현황" 필터가 아무 일도 안 함. 쿼리 파라미터는 소문자(`ongoing`)로 들어오는데 MyBatis 조건은 `f.status == 'ONGOING'` 대문자 비교라 `<if>` 가 항상 거짓 → 조건절이 통째로 빠져 `all`/`ongoing`/`ended` 가 전부 같은 505건. 예외가 안 나서 화면상 멀쩡해 보이는 게 함정 | `PerformanceListService.buildFilter()` 에서 `toUpperCase(Locale.ROOT)` 로 정규화. MyBatis 동적 SQL 은 조건이 안 맞아도 조용히 넘어가므로, 필터를 추가할 땐 값별 건수가 실제로 갈리는지 확인하는 습관이 필요 |
| 검색 결과 0건일 때 `POST /api/admin/sync` 안내(관리자용)가 일반 사용자에게 노출. `result.total` 이 *필터 적용 후* 건수라 "DB 가 비었다" 와 "검색어가 안 맞다" 를 구분 못 함 | 0건일 때만 필터 없는 `countPerformances` 를 한 번 더 실행해 `libraryEmpty` 를 따로 판정. 평상시엔 추가 쿼리 없음 |
| 목록의 문화정보 포스터가 전부 안 뜸(프록시가 404). 원본은 200 + `image/gif` 를 주는데, `culture.go.kr` 은 http 요청을 **https 로 302** 시키고 `HttpURLConnection` 은 프로토콜이 바뀌는 리다이렉트를 자동으로 안 따라간다. 그래서 140바이트짜리 리다이렉트 HTML 을 이미지로 읽으려다 `ImageIO.read()` 가 `null` 을 반환. 게다가 실패를 캐시하지 않아 목록을 열 때마다 죽은 URL 로 매번 외부 요청이 나갔다 | `setInstanceFollowRedirects(false)` 로 두고 `Location` 을 직접 따라간다. **리다이렉트 대상 호스트를 매 홉마다 허용 목록으로 다시 검사**한다 — 안 하면 리다이렉트로 SSRF 허용 목록을 우회할 수 있다. 실패 URL 은 10분간 기억해 재요청을 막고, 캐시는 건수 상한 + 초과 시 전체 삭제에서 **바이트 상한 + LRU** 로 바꿨다(전체 삭제 방식은 목록을 넓게 볼 때 캐시가 통째로 날아간다) |
| `/` 로 들어가면 로그인 화면으로 튕김. `SecurityConfig` 에서 `/` 는 `permitAll` 인데도 302 가 났다. 실제 원인은 인증이 아니라 목록 조회 NPE — 공연장명이 없는 공연(문화정보 23건)이 `GROUP BY p.venue` 에서 `label = null` 인 패싯 행을 만들고, `row.getLabel().equals(selected)` 에서 터졌다. 컨트롤러 예외가 `/error` 로 포워딩되는데 `/error` 는 `permitAll` 목록에 없어서 로그인 리다이렉트로 보였다 | `selectVenueCounts` 에서 공연장명이 비어 있는 행을 제외하고, 비교도 `Objects.equals` 로 바꿨다. **로그인으로 튕긴다고 인증 설정부터 뒤지면 안 된다** — 서버 로그의 실제 예외를 먼저 볼 것. 필터 값이 될 수 없는 NULL 은 집계 쿼리 단계에서 거르는 게 맞다 |
| KOPIS 좌표를 채우는 코드를 넣었는데 기존 500여 건은 계속 비어 있음. `toEntity()`(신규 저장)에는 `latitude`/`longitude` 가 있지만 `updateFromExternal()`(기존 갱신)의 파라미터에는 없어서, 이미 적재된 공연은 아무리 다시 수집해도 좌표가 반영되지 않는 구조였음 | `updateFromExternal()` 에 좌표 파라미터 추가. **신규 저장 경로와 갱신 경로에 필드가 따로 나열되는 구조**라, 엔티티에 필드를 추가할 때는 양쪽을 같이 손대야 한다. 갱신 경로는 `if (값 != null)` 로 덮어써서, 소스가 좌표를 못 준 회차에 기존 값이 지워지지 않게 했다 |
| 문화정보 수집이 매번 정확히 50건(10건 × 5페이지)에서 멈춤. 페이징 파라미터를 KOPIS 와 같은 `cPage`/`rows` 로 보냈는데 이 API 는 `PageNo`/`numOfrows` 만 인식하고, **모르는 파라미터는 오류 없이 무시**한 뒤 1페이지 10건을 돌려줌 → 5페이지를 도는 내내 같은 10건을 재수신하고 있었고, `external_id` 가 같으니 전부 갱신 처리돼 신규 0건. 전체 1000건 중 10건만 본 셈 | `PageNo`/`numOfrows` 로 교체. `cPage=2` 와 `PageNo=2` 응답의 `seq` 를 직접 비교해 확인했다. 외부 API 페이징은 **응답 본문의 `PageNo`/`numOfrows` 에코 값이 요청과 일치하는지** 확인해야지, 건수만 보면 조용히 같은 페이지를 반복해도 모른다 |
| `POST /api/admin/sync` 가 세 소스 모두 `received: 0` 인데 에러 로그가 없음. KOPIS 는 인증키 오류도 정상 응답과 똑같은 `<dbs><db>` 구조(`<returncode>02</returncode><errmsg>SERVICE KEY IS NOT REGISTERED ERROR</errmsg>`)로 200 을 주는데, 클라이언트는 `db` 노드 **유무**만 보고 인증키 오류를 판정하다 보니 오류 응답이 그냥 "필드 없는 공연 1건"으로 파싱돼 `isValid()` 에서 조용히 버려짐 | `parseList()` 에 `errmsg` 검사(`isErrorResponse`)를 추가해 오류 응답이면 `returncode`/`errmsg` 를 경고 로그로 남기고 빈 리스트 반환. 외부 API 는 오류도 200 + 정상 스키마로 오는 경우가 있으니 **HTTP 상태·노드 유무가 아니라 응답 본문의 에러 필드로 판정**할 것 |
| KOPIS 상세(prfcast, 출연진)를 담을 엔티티 필드명을 `cast` 로 지었더니 앱 기동 시 `ReservationConcurrencyTest` 전체가 `SQLGrammarException` 으로 실패. `CAST` 가 SQL 예약어(형변환 함수)라 Hibernate 가 만든 DDL/쿼리와 충돌 | 필드명을 `castMembers` 로 변경. JPA 필드/컬럼명을 지을 때 SQL 예약어(CAST, ORDER, GROUP, KEY 등)와 겹치지 않는지 먼저 확인하는 습관 필요 |

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
#    - 전국공연행사정보표준데이터 (공연장 주소/좌표/객석수도 이 안에 포함됨)
#    - 한눈에보는문화정보조회서비스

# 1-1) KOPIS(공연예술통합전산망)는 별도 사이트(www.kopis.or.kr)에서 인증키 발급
#      data.go.kr 키와 다른 키이므로 재사용 불가

# 2) 환경변수로 주입 (application.yml 에 직접 넣지 말 것)
export PUBLICDATA_SERVICE_KEY="발급받은_인코딩된_키"
export KOPIS_SERVICE_KEY="발급받은_KOPIS_키"

# 2-1) 결제 기능을 쓰려면 토스페이먼츠 테스트 키도 발급 (developers.tosspayments.com)
export TOSS_CLIENT_KEY="test_ck_..."
export TOSS_SECRET_KEY="test_sk_..."

# 2-2) 공연 상세의 공연장 위치 지도를 쓰려면 네이버 클라우드 플랫폼 Maps 신청
#      console.ncloud.com > Maps > Application 등록, Dynamic Map 선택,
#      Web 서비스 URL 에 http://localhost:8080 등록
#      (키가 없어도 앱은 정상 동작한다 — 지도 없이 길찾기 링크만 노출)
export NAVER_MAP_CLIENT_ID="발급받은_키"

# 3) 실행 (H2, 파일 기반 - data/ 에 저장돼 재시작해도 유지된다)
./gradlew bootRun

# 4) 공공데이터 수동 수집 (ROLE_ADMIN 필요 — 로컬 시드 계정 admin/admin)
curl -u admin:admin -X POST http://localhost:8080/api/admin/sync

#    전체 재수집이 필요하면 (기본은 KOPIS 증분 수집)
curl -u admin:admin -X POST "http://localhost:8080/api/admin/sync?full=true"

# 5) 접속
open http://localhost:8080
```

로컬 프로필은 `LocalDataSeeder` 가 최초 기동 시 공연 3개 + 테스트 계정(user/user,
admin/admin)을 자동으로 채워주므로, 공공데이터 API 키 없이도 화면 확인이 가능하다.
`ReservationConcurrencyTest` 는 별도 `test` 프로필(인메모리 H2)을 써서 로컬 개발 DB와
분리돼 있다 — 테스트를 돌려도 로컬에 쌓아둔 데이터가 지워지지 않는다.

H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/ticket`)

### Docker 로 실행 (MariaDB, prod 프로파일)

```bash
export DB_USER=ticket
export DB_PASSWORD=ticket
export PUBLICDATA_SERVICE_KEY="발급받은_인코딩된_키"
export KOPIS_SERVICE_KEY="발급받은_KOPIS_키"
export TOSS_CLIENT_KEY="test_ck_..."
export TOSS_SECRET_KEY="test_sk_..."
# 선택 — 없으면 해당 기능만 빠지고 앱은 정상 기동된다
export NAVER_MAP_CLIENT_ID="발급받은_지도_키"
export KAKAO_CLIENT_ID="..."      # 소셜 로그인
export KAKAO_CLIENT_SECRET="..."
export NAVER_CLIENT_ID="..."
export NAVER_CLIENT_SECRET="..."

docker compose up --build
```

`docker-compose.yml` 은 위 환경변수를 전부 전달하며, 없으면 `REPLACE_ME` 로 대체된다.
**`KOPIS_SERVICE_KEY` 를 빠뜨리면 공연 대부분을 차지하는 KOPIS 소스가 통째로 수집되지
않는다** — 이 경우 동기화 로그에 `KOPIS 목록 API 오류 응답` 경고가 남는다.

`prod` 프로파일에는 `LocalDataSeeder` 가 동작하지 않아 **관리자 계정이 생성되지 않는다.**
`/api/admin/**` 는 `ROLE_ADMIN` 이 필요하므로, 배포 환경에서는 관리자 계정을 따로
만들어야 수동 동기화를 호출할 수 있다.

앱은 `http://localhost:8080`, MariaDB 는 `localhost:3306` 로 노출된다.
`docker-compose.yml` 은 `prod` 프로파일로 기동하며, `application.yml` 의
데이터소스 URL(`localhost` 고정)은 컨테이너 네트워크용 `SPRING_DATASOURCE_URL`
환경변수로 덮어쓴다.

---

## 8. API

| Method | URL | 설명 |
|---|---|---|
| GET | `/` | 공연 목록 |
| GET | `/login`, `/signup` | 로그인 / 회원가입 |
| GET | `/performances/{id}` | 공연 상세 + 회차 |
| GET | `/schedules/{id}/seats` | 좌석 배치도 |
| POST | `/api/seats/{seatId}/hold?strategy=` | 좌석 선점 (로그인 필요, 전략 선택 가능) |
| GET | `/reservations/{no}/payment` | 결제 페이지 (결제위젯) |
| GET | `/reservations/{no}/payment/success` | 결제 승인 콜백 - 서버가 amount 대조 검증 후 확정 |
| GET | `/reservations/{no}/payment/fail` | 결제 실패 콜백 |
| POST | `/api/reservations/{no}/cancel` | 취소 + 환불액 계산 (결제완료 건은 토스 취소 API 호출) |
| GET | `/mypage/reservations` | 마이페이지 예매 내역 (본인 예매만, 상태별 결제/취소 액션) |
| GET | `/admin/dashboard?from=&to=` | 관리자 대시보드 (ROLE_ADMIN) - 일자별 매출 / TOP5 / 등급별 비중 |
| POST | `/api/admin/sync` | 공공데이터 수동 수집. 소스별(`standard`/`culture`) 수신/신규/스킵 건수와 성공 여부를 분리해서 반환 |
| POST | `/api/webhooks/toss` | 토스 결제 상태 웹훅 (가상계좌 입금완료 등 비동기 반영) |

환불 수수료: 10일 전 0% / 7일 전 10% / 3일 전 20% / 1일 전 30% / 당일 취소 불가

---

## 9. 남은 작업

- [x] Spring Security + 회원 도메인 (세션 기반 폼 로그인, `memberId` 파라미터 제거)
- [x] 토스페이먼츠 테스트 결제 연동 (결제위젯 3.0) + 웹훅으로 결제 상태 동기화
- [x] 관리자 대시보드 (`selectDailySales` + Chart.js, ROLE_ADMIN 전용)
- [x] 마이페이지 예매 내역 / 취소 화면
- [x] GitHub Actions CI (push/PR 시 빌드 + `ReservationConcurrencyTest` 포함 전체 테스트 자동 실행)
- [x] Docker (`Dockerfile` 멀티스테이지 빌드 + `docker-compose.yml` app/MariaDB)
- [x] 문화정보(CIA-) 연동 정상화 — 실제 엔드포인트/XML 파싱으로 재작성, 소스별 동기화 실패 격리 + 결과 분리 응답
- [x] 동기화 데이터 정제 — 종료된 공연·상설전시류(90일 초과) 제외, 홈 화면 공연 목록 월별 그룹핑
- [x] 로컬 개발 DB 영속화 (H2 파일 기반) + 테스트 전용 프로필 분리
- [x] KOPIS 연동 — 목록+상세 조회, dtguidance(실제 회차시간)/pcseguidance(등급별 실제가격) 반영. 실제 서비스키로 라이브 호출 검증 완료
- [x] KOPIS 공연시설(prfplc) 연동 — 상세의 mt10id/mt13id 로 실제 좌석수(seatscale) 조회, 공연장 단위 캐시로 중복 호출 방지. 라이브 호출로 실제 좌석수(200석 고정이 아닌 20~458석 등 다양한 값) 반영 확인
- [x] 다중 좌석 예매 — 예매 1건이 좌석 여러 개를 갖도록 도메인 확장(FK 를 `seat.reservation_id` 로 이동). 좌석은 항상 id 오름차순으로 잠가 데드락 방지, 하나라도 실패하면 전체 롤백(`PartialSeatHoldException`)
- [x] Redis 분산 락(`DISTRIBUTED`) — `SET NX PX` + Lua CAS 해제, 5번째 전략으로 동일 조건 비교. Redis 없이도 앱 기동, 테스트는 embedded-redis 로 Docker 없이 측정
- [x] KOPIS 증분 수집(`afterdate`) — 소스별 마지막 성공일을 `sync_state` 에 남기고, 다음 배치는 그 이후 등록/수정된 건만 조회. 라이브 측정 결과 수신 500건 → 214건, 소요 500초 → 131초로 감소. 이력이 없으면(최초 수집·DB 초기화) 자동으로 전체 수집, `POST /api/admin/sync?full=true` 로 강제 전체 재수집
- [x] KOPIS 공연 위경도·도로명주소 채우기 — 목록/상세에는 좌표가 없고 공연시설상세(`prfplc`)에만 `la`/`lo`/`adres` 가 있다. 이미 좌석수 때문에 호출하던 응답에서 같이 뽑아 **추가 호출 없이** 반영. KOPIS 좌표 0건 → 500건, 주소도 시도명("서울특별시")에서 도로명("서울특별시 중구 을지로 12")으로 승격
- [x] 회차 시각을 실측 분포 기반으로 전환 — 원본에 공연 시각이 없을 때 쓰던 "19:00 고정"을 KOPIS 시간대별 상연 통계(`boxStatsTime`) 실측 분포로 교체(`showtime-distribution.yml`). 최근 30일 상연 24,186회 기준 09~12시 10.4% / 12~15시 21.8% / 15~18시 29.9% / 18~21시 36.3%. 공연ID+날짜를 seed 로 쓰는 결정적 선택이라 배치를 다시 돌려도 같은 결과가 나온다
- [ ] 카카오/네이버 소셜 로그인 — `spring-boot-starter-oauth2-client` + `CustomOAuth2UserService` 로 최초 로그인 시 자동 회원가입까지 구현 완료. 카카오/네이버 개발자 콘솔에서 REST API 키 발급 및 리다이렉트 URI 등록 대기 중(`KAKAO_CLIENT_ID/SECRET`, `NAVER_CLIENT_ID/SECRET` 환경변수 미설정 상태)
- [ ] AWS EC2 + RDS 배포 (CD)
