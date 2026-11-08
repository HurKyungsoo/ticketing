-- KOPIS 상세를 다시 받아 본 날짜. (mariadb 쪽 V11 과 같은 내용)
--
-- 수집 때 상세 호출이 막혀 가격·공연장 정보 없이 들어온 공연을 매일 정해진 양만큼 다시 받는다.
-- 받아 봤는데도 원본에 정보가 없는 공연을 매일 다시 고르지 않도록, 시도한 날을 남긴다.
alter table performance add column detail_fetched_on date;
