-- 마이페이지에서 "삭제"한 예매를 표시하는 컬럼. 예매는 영수증 성격이라 행을 지우지 않고
-- 이 사용자의 목록 화면에서만 뺀다(취소/환불 이력·매출 집계는 그대로 남는다).
alter table reservation add column hidden_at timestamp(6);
