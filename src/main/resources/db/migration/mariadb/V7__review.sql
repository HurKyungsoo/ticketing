-- 관람평. (h2 쪽 V7 과 같은 내용, 타입 표기만 다르다)
--
-- 아무나 못 쓴다 — 확정된 예매가 있고 관람일이 지난 사람만(ReviewService.write).
-- 그 판정은 예매·회차를 봐야 해서 애플리케이션이 하고, DB 는 아래 두 가지만 보장한다.
--
-- member_id 는 FK 를 걸지 않는다. wishlist·reservation·notification 과 같은 방식으로 표를 맞췄다.
-- performance_id 는 FK 를 건다 — 수집 규칙에 안 맞는 공연은 실제로 지워지므로
-- (PerformanceSyncService.purgeNonPerformances) 참조가 남으면 안 된다.
create table review (
    id             bigint not null auto_increment,
    member_id      bigint not null,
    performance_id bigint not null,
    -- 작성 시점 닉네임 스냅샷. 닉네임이 바뀌어도 그때 쓴 이름으로 남고,
    -- 목록을 그릴 때 member 를 조인하지 않아도 된다.
    nickname       varchar(20) not null,
    rating         int not null,
    content        varchar(500) not null,
    created_at     datetime(6) not null,
    primary key (id),
    -- 별점 범위는 화면·서버가 이미 막지만, 직접 INSERT 하는 경로까지 생각하면
    -- 마지막 방어는 DB 가 하는 게 맞다. 평균이 조용히 틀어지는 걸 막는다.
    constraint ck_review_rating check (rating between 1 and 5)
) engine=InnoDB;

-- 한 공연에 한 사람이 하나만. 여러 개를 허용하면 한 사람이 평균을 여러 번 밀 수 있다.
alter table review add constraint uk_review unique (member_id, performance_id);

-- 공연 상세가 "이 공연의 관람평을 최근 순으로" 읽는다.
create index idx_review_performance on review (performance_id, created_at);

alter table review
    add constraint fk_review_performance foreign key (performance_id) references performance (id);
