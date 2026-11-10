-- KOPIS 상세의 공연 시각(dtguidance)과 가격(pcseguidance) 원문. (mariadb 쪽 V12 와 같은 내용)
--
-- 지금까지는 회차를 만들 때 한 번 파싱해 쓰고 버렸다. 나중에 회차·좌석을 실제 시각과 가격으로
-- 다시 만들려면 KOPIS 를 다시 불러야 했다. 원문을 남겨 두면 저장된 값만으로 다시 만들 수 있다.
alter table performance add column show_time_guidance varchar(2000);
alter table performance add column price_guidance varchar(2000);

-- 상세 보충으로 이미 받아 본 공연도 원문이 없으므로 다시 받게 한다.
update performance set detail_fetched_on = null where detail_fetched_on is not null;
