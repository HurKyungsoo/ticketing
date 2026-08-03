-- 찜한 공연. (h2 쪽 V2 와 같은 내용, 타입 표기만 다르다)
--
-- member_id 는 FK 를 걸지 않는다. reservation.member_id 도 같은 방식이라 표를 맞췄다.
-- performance_id 는 FK 를 건다 — 수집 규칙에 안 맞는 공연은 실제로 지워지므로
-- (PerformanceSyncService.purgeNonPerformances) 참조가 남으면 안 된다.
create table wishlist (
    id             bigint not null auto_increment,
    member_id      bigint not null,
    performance_id bigint not null,
    created_at     datetime(6) not null,
    primary key (id)
) engine=InnoDB;

-- 같은 공연을 두 번 찜할 수는 없다. 화면에서 막아도 버튼을 따닥 누르면 요청이 둘 나가므로
-- 최종 방어는 DB 가 한다 (WishlistService 가 이 위반을 "이미 찜함"으로 해석한다).
alter table wishlist add constraint uk_wishlist unique (member_id, performance_id);

-- 마이페이지 찜 목록은 "내 것을 최근 순으로"만 조회한다.
create index idx_wishlist_member on wishlist (member_id, created_at);

alter table wishlist
    add constraint fk_wishlist_performance foreign key (performance_id) references performance (id);
