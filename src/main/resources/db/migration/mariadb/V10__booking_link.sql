-- 공연 하나에 붙는 실제 예매처 목록(KOPIS 상세의 relates). (h2 쪽 V10 과 같은 내용,
-- 타입 표기만 다르다)
--
-- 인터파크 같은 대형 예매처뿐 아니라 공연장 자체 예매 페이지도 온다. 이 사이트의
-- 회차·좌석·가격은 SeatGenerator 가 만든 추정치라, 상세 화면은 이 목록으로 "진짜
-- 예매 가능한 곳"을 안내한다 — KOPIS 상세 페이지 링크보다 한 단계 더 정확하다.
--
-- Performance.bookingLinks 의 @ElementCollection 테이블이라 별도 PK가 없다 — 소유자
-- 없이는 존재하지 않는 값 목록이라 (performance_id, sort_order) 조합이 곧 식별자다.
-- performance_id 는 FK 를 건다. wishlist 와 같은 이유(PerformanceSyncService.
-- purgeNonPerformances) 이지만, 여기는 @ElementCollection 이라 Performance 삭제 시
-- Hibernate 가 알아서 같이 지운다 — cascade 설정을 따로 안 해도 된다.
create table booking_link (
    performance_id bigint       not null,
    sort_order      integer      not null,
    name            varchar(100) not null,
    url             varchar(500) not null,
    primary key (performance_id, sort_order)
) engine=InnoDB;

alter table booking_link
    add constraint fk_booking_link_performance foreign key (performance_id) references performance (id);
