-- 회차·좌석을 저장된 KOPIS 원문으로 다시 만든 날짜. (h2 쪽 V13 과 같은 내용)
alter table performance add column schedules_rebuilt_on date;
