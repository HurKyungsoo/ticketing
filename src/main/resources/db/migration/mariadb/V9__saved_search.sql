-- 저장한 검색. (h2 쪽 V9 와 같은 내용, 타입 표기만 다르다)
--
-- 목록 화면에서 "이 조건 저장"을 누르면 그때의 필터(장르·지역·월·검색어)를 남긴다.
-- 매일 새벽 공연 수집 배치가 새 공연을 등록할 때마다 저장된 조건과 맞춰 보고, 맞으면
-- 알림을 만든다(NotificationType.SAVED_SEARCH_MATCH).
--
-- 요일·시간대·공연장은 저장 대상에서 뺐다 — 이유는 h2 쪽 V9 주석 참고.
create table saved_search (
    id            bigint not null auto_increment,
    member_id     bigint not null,
    label         varchar(100) not null,
    category      varchar(30),
    region        varchar(50),
    -- "month" 는 예약어라 그대로 쓰면 구문 오류가 난다(h2 쪽 V9 주석 참고).
    search_month  integer,
    keyword       varchar(100),
    created_at    datetime(6) not null,
    primary key (id)
) engine=InnoDB;

-- 마이페이지 "저장한 검색" 목록과 배치의 매칭 조회 둘 다가 쓴다.
create index idx_saved_search_member on saved_search (member_id);
