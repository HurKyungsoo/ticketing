-- 회차·좌석을 저장된 KOPIS 원문(실제 공연 시각·가격)으로 다시 만든 날짜. (mariadb 쪽 V13 과 같은 내용)
-- 한 번 다시 만든 공연을 매일 다시 고르지 않으려고 남긴다.
alter table performance add column schedules_rebuilt_on date;
