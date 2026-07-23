# CLAUDE.md

공연 예매 시스템 "객석" 프로젝트 작업 규칙.

## 프로젝트 개요

공공데이터포털의 공연 정보를 배치 수집해 좌석/회차 도메인을 자체 생성하고,
한 좌석에 대한 동시 예매 경쟁을 4가지 전략으로 제어·비교하는 포트폴리오 프로젝트.

**이 프로젝트의 핵심 가치는 동시성 제어다.** 기능을 늘리는 것보다
동시성 관련 코드의 정확성과 설명 가능성이 우선한다.

## 스택

- Java 21 / Spring Boot 3.3.0 / Gradle
- JPA(Hibernate) + MyBatis 3.0.3
- MariaDB(prod) / H2(local, test)
- Thymeleaf

## 빌드 · 실행

```bash
./gradlew build
./gradlew bootRun                                    # local 프로파일, H2 인메모리
./gradlew test --tests '*ReservationConcurrencyTest*' # 동시성 검증
```

환경변수 `PUBLICDATA_SERVICE_KEY` 가 없으면 외부 API 호출은 빈 리스트를 반환한다.
(앱은 정상 기동됨)

## 아키텍처 규칙

### JPA vs MyBatis
- **JPA**: 엔티티 CRUD, 락 제어, 연관관계 탐색
- **MyBatis**: 좌석 배치도 조회, 매출 집계 등 동적/집계 쿼리
- 새 조회를 추가할 때 위 기준으로 판단할 것. 애매하면 JPA.

### 패키지 책임
```
domain/      엔티티 + 상태 전이 메서드. 비즈니스 규칙은 여기에.
repository/  JPA 리포지토리. 락 정의는 여기 @Lock 으로.
external/    공공데이터 API 클라이언트 + 정규화 + 수집 배치.
service/     트랜잭션 경계. 락 획득 순서 관리.
mapper/      MyBatis 인터페이스 + DTO.
controller/  요청 검증과 응답 변환만. 로직 금지.
```

### 엔티티 규칙
- `@Setter` 금지. 상태 변경은 의미 있는 메서드로만 (`hold()`, `sell()`, `release()`).
- `@NoArgsConstructor(access = PROTECTED)` 유지.
- 연관관계는 전부 `LAZY`.
- 새 엔티티에도 `@Builder` 패턴 유지.

## 동시성 관련 절대 규칙

이 항목들을 어기면 프로젝트의 존재 이유가 사라진다. 수정 전 반드시 확인할 것.

1. **락 획득 순서는 항상 `Seat` → `PerformanceSchedule`.**
   순서를 뒤집으면 데드락이 난다. 새 로직 추가 시 이 순서를 지킬 것.

2. **`findByIdForUpdate` 에서 `@QueryHints` 락 타임아웃(3초)을 제거하지 말 것.**
   타임아웃 없는 `SELECT ... FOR UPDATE` 는 오픈런 때 커넥션 풀을 통째로 묶는다.

3. **낙관적 락 재시도는 트랜잭션 밖에서만.**
   `ReservationFacade.holdWithRetry()` 가 그 역할이다.
   `ReservationService` 안으로 재시도 루프를 옮기지 말 것.

4. **`HoldStrategy.NONE` 경로를 삭제하지 말 것.**
   버그 재현용이며 포트폴리오의 비교 근거다. "죽은 코드"로 판단해 지우지 말 것.

5. **동시성 관련 코드를 수정하면 반드시 `ReservationConcurrencyTest` 를 돌릴 것.**
   `PESSIMISTIC` / `OPTIMISTIC` / `UNIQUE` 는 성공 1건이어야 한다.

## 외부 API 규칙

- `serviceKey` 는 이미 URL 인코딩된 문자열이다.
  `UriComponentsBuilder.build(true)` 를 `build()` 로 바꾸면 재인코딩되어 401 이 난다.
- API 응답 파싱 실패는 **예외를 던지지 말고 해당 건만 스킵**한다.
  한 건 때문에 배치 전체가 죽으면 안 된다.
- 새 필드를 파싱할 때는 `PublicDataParser` 의 후보 키 방식을 따를 것.
  기관마다 필드명이 다르다.
- API 키를 코드나 `application.yml` 에 하드코딩하지 말 것. 환경변수만.

## 작업 방식

- 커밋 메시지는 한국어, `feat:` / `fix:` / `refactor:` / `docs:` 접두어.
- 한 번에 한 기능. 여러 파일을 동시에 크게 바꾸지 말 것.
- 새 기능 추가 시 README 의 "남은 작업" 체크박스를 갱신할 것.
- 트러블슈팅 사례가 생기면 README 5번 표에 한 줄 추가할 것. (포트폴리오 자산)

## 하지 말 것

- Lombok `@Data`, `@Setter` 사용
- 컨트롤러에 비즈니스 로직 작성
- `application.yml` 에 실제 키/비밀번호 커밋
- 동시성 테스트를 `@Disabled` 처리
- 기존 엔티티에 `EAGER` 페치 추가

## 다음 작업 순서

1. Spring Security + 회원 도메인 (현재 `memberId` 파라미터 임시 처리 중)
2. 토스페이먼츠 테스트 결제 + 웹훅
3. 관리자 대시보드 (`selectDailySales` + Chart.js)
4. Docker + GitHub Actions CI
5. AWS EC2 + RDS 배포
