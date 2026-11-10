-- KOPIS 상세의 공연 시각(dtguidance)과 가격(pcseguidance) 원문. (h2 쪽 V12 와 같은 내용, 이유는 h2 쪽 주석 참고)
alter table performance add column show_time_guidance varchar(2000);
alter table performance add column price_guidance varchar(2000);

update performance set detail_fetched_on = null where detail_fetched_on is not null;
