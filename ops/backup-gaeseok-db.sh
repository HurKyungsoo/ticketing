#!/bin/bash
# 객석 운영 DB(ticket) 전체 덤프 — 하루 한 번 크론으로 돈다.
#
# 서버 위치: /home/ubuntu/gaeseok/backup-gaeseok-db.sh
# 크론(ubuntu, 서버 시계는 UTC): 0 20 * * *  → 한국 시간 05:00.
#   04:00 수집 배치(평소 10분 안팎)가 끝난 뒤, 05:30 OS 자동 업데이트(apt-daily-upgrade —
#   MariaDB 패키지가 올라오면 DB 를 재시작한다)보다 앞. 덤프는 30초 안쪽이다.
#
# - seat 까지 전부 뜬다. 어느 좌석이 팔렸는지(seat.reservation_id, status)는 seat 에만 있어서,
#   빼고 복구하면 예매는 있는데 그 예매의 좌석을 모른다.
# - --single-transaction 이라 테이블을 잠그지 않는다. 덤프 중에도 예매·조회가 그대로 된다.
# - 덤프 끝의 "Dump completed" 줄까지 확인해야 성공으로 친다. 중간에 끊긴 파일은 지운다.
# - 덤프에는 회원 정보가 들어 있다. 디렉터리 700, 파일 600.
#
# 복구: zstd -dc ticket-YYYYMMDD-HHMM.sql.zst | sudo mariadb <DB 이름>
set -euo pipefail

DB=ticket
DEST_DIR=/home/ubuntu/gaeseok/backups
LOG="$DEST_DIR/backup.log"
RETAIN_DAYS=7

umask 077
mkdir -p "$DEST_DIR"
chmod 700 "$DEST_DIR"

log() { echo "$(TZ=Asia/Seoul date '+%F %T') $*" >> "$LOG"; }

TS=$(TZ=Asia/Seoul date '+%Y%m%d-%H%M')
OUT="$DEST_DIR/ticket-$TS.sql.zst"
START=$(date +%s)

# 우선순위를 낮춰 앱과 DB 응답을 먼저 챙긴다.
if nice -n 10 ionice -c2 -n7 \
        sudo -n mariadb-dump --single-transaction --quick --routines --triggers \
        --default-character-set=utf8mb4 "$DB" \
    | zstd -q -3 > "$OUT.tmp" \
    && zstd -dc "$OUT.tmp" | tail -n 1 | grep -q 'Dump completed'; then
    mv "$OUT.tmp" "$OUT"
    SIZE=$(du -h "$OUT" | cut -f1)
    log "백업 완료: $(basename "$OUT") ($SIZE, $(( $(date +%s) - START ))초)"
else
    rm -f "$OUT.tmp"
    log "백업 실패 ($(( $(date +%s) - START ))초)"
    exit 1
fi

# 보관 기간 지난 것 정리 — 성공했을 때만 지운다(실패가 이어져도 옛 백업은 남는다).
find "$DEST_DIR" -name 'ticket-*.sql.zst' -mtime "+$((RETAIN_DAYS - 1))" -delete
