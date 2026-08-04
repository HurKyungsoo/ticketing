-- 로컬 계정 비밀번호 재설정과 예매 알림에 쓸 이메일. (h2 쪽 V3 와 같은 내용, 타입 표기만 다르다)
-- 카카오/네이버는 지금 요청 scope(profile_nickname / name)에 이메일이 없어 안 준다.
-- 그래서 OAuth 계정은 계속 null 이고, null 은 유니크 인덱스에서 여러 번 허용된다.
alter table member add column email varchar(190);
alter table member add constraint uk_member_email unique (email);

-- 비밀번호 재설정 토큰. member_id 는 다른 표(wishlist, reservation)와 같은 이유로 FK 를 안 건다.
-- 메일 발송이 실패해도(SMTP 미설정 등) 토큰 자체는 남아 있어야 링크가 계속 유효하므로,
-- 발송과 토큰 생성을 한 트랜잭션에 묶지 않는다.
create table password_reset_token (
    id         bigint not null auto_increment,
    member_id  bigint not null,
    token      varchar(64) not null,
    expires_at datetime(6) not null,
    used_at    datetime(6),
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

alter table password_reset_token add constraint uk_password_reset_token unique (token);
create index idx_password_reset_token_member on password_reset_token (member_id);
