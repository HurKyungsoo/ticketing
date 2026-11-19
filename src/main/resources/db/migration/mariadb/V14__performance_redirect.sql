-- 다른 소스의 같은 공연을 합쳐 지운 공연 → 남긴 공연. (h2 쪽 V14 와 같은 내용)
-- 지운 공연의 옛 주소(최근 본 공연·공유 링크·검색엔진)가 404 대신 남은 공연으로 가게 한다.
-- 남긴 공연도 나중에 지워질 수 있으므로 외래키를 걸지 않고, 쓸 때 있는지 확인한다.
create table performance_redirect (
    from_id    bigint not null,
    to_id      bigint not null,
    created_at datetime(6) not null,
    primary key (from_id)
);
create index idx_performance_redirect_to on performance_redirect (to_id);
