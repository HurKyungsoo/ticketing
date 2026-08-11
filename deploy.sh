#!/usr/bin/env bash
#
# 객석 배포 — 빌드 → 전송 → 재시작 → 헬스체크, 실패하면 이전 jar 로 되돌린다.
#
#   DEPLOY_HOST=ubuntu@1.2.3.4 ./deploy.sh
#
# 되돌리기를 넣은 이유: 이 인스턴스에는 다른 서비스 둘(WeddingCard 8080, LIBRE 8081)이
# 같이 돌고 있고 메모리가 빠듯하다. 새 jar 가 안 뜨는 채로 방치되면 그 사이 사이트가
# 죽어 있는 것은 물론, 원인을 찾는 동안 수동으로 되돌려야 한다. 실패를 감지하는 김에
# 되돌리는 데까지 가는 편이 낫다.
#
# 배포 대상은 환경변수로 받는다:
#   DEPLOY_HOST  필수. 예: ubuntu@1.2.3.4
#   DEPLOY_KEY   기본 ~/.ssh/lightsail_gaeseok
#   DEPLOY_URL   기본 https://gaekseok.com  (헬스체크 대상)
#
# 서버 주소를 기본값으로 박아두지 않는 이유: 이 저장소는 공개고, 도메인은
# Cloudflare 뒤에 있어 원본 IP 가 가려져 있다. 스크립트에 적어두면 그 가림막이
# 무의미해진다.
#
# 매번 치기 번거로우면 옆에 .env.deploy 를 만들어 `DEPLOY_HOST=ubuntu@1.2.3.4`
# 한 줄만 넣어두면 된다. .gitignore 의 `.env.*` 에 걸려 커밋되지 않는다.

set -euo pipefail

# 명령줄로 준 값이 항상 이긴다. 파일은 값이 없을 때만 읽는다 —
# 반대로 하면 임시로 다른 서버에 배포하려고 앞에 붙인 값이 조용히 무시된다.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${DEPLOY_HOST:-}" ] && [ -f "$SCRIPT_DIR/.env.deploy" ]; then
    . "$SCRIPT_DIR/.env.deploy"
fi

HOST="${DEPLOY_HOST:?DEPLOY_HOST 를 지정하세요 (.env.deploy 에 적어두거나 DEPLOY_HOST=ubuntu@1.2.3.4 ./deploy.sh)}"
KEY="${DEPLOY_KEY:-$HOME/.ssh/lightsail_gaeseok}"
URL="${DEPLOY_URL:-https://gaekseok.com}"
REMOTE_DIR="~/gaeseok"
SERVICE="gaeseok"

SSH=(ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=15 "$HOST")

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ── 1. 빌드 ────────────────────────────────────────────────────────────────
# build.gradle 이 GRADLE_BUILD_DIR 이 있으면 산출물을 그쪽으로 보낸다(OneDrive 회피).
# 그래서 jar 경로가 환경에 따라 달라진다 — 여기서 같은 규칙으로 찾는다.
say "빌드"
./gradlew bootJar -q

BUILD_DIR="${GRADLE_BUILD_DIR:-build}"
JAR=$(ls -t "$BUILD_DIR"/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)
[ -n "$JAR" ] || die "jar 을 못 찾았다: $BUILD_DIR/libs/"
printf '  %s (%s)\n' "$(basename "$JAR")" "$(du -h "$JAR" | cut -f1)"

# ── 2. 이전 jar 백업 ───────────────────────────────────────────────────────
say "이전 버전 백업"
"${SSH[@]}" "cp $REMOTE_DIR/app.jar $REMOTE_DIR/app.jar.prev 2>/dev/null && echo '  app.jar.prev 저장' || echo '  (이전 jar 없음 — 첫 배포)'"

# ── 3. 전송 ────────────────────────────────────────────────────────────────
# 임시 이름으로 올린 뒤 옮긴다. 전송이 중간에 끊겨도 돌고 있는 app.jar 은 온전하다.
say "전송"
scp -i "$KEY" -o BatchMode=yes -q "$JAR" "$HOST:$REMOTE_DIR/app.jar.new"
"${SSH[@]}" "mv $REMOTE_DIR/app.jar.new $REMOTE_DIR/app.jar"

# ── 4. 재시작 ──────────────────────────────────────────────────────────────
say "재시작"
"${SSH[@]}" "sudo systemctl restart $SERVICE"

# ── 5. 헬스체크 ────────────────────────────────────────────────────────────
# 기동에 20 초 안팎 걸리고, 메모리가 빠듯해 더 걸릴 때도 있어 넉넉히 본다.
say "헬스체크"
OK=0
for i in $(seq 1 30); do
  CODE=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$URL/actuator/health" || true)
  if [ "$CODE" = "200" ]; then OK=1; printf '  UP (%d초)\n' "$((i*3))"; break; fi
  printf '  대기 %d초... (HTTP %s)\r' "$((i*3))" "${CODE:-000}"
  sleep 3
done

if [ "$OK" != "1" ]; then
  printf '\n'
  say "실패 — 이전 버전으로 되돌린다"
  "${SSH[@]}" "test -f $REMOTE_DIR/app.jar.prev && mv $REMOTE_DIR/app.jar.prev $REMOTE_DIR/app.jar && sudo systemctl restart $SERVICE && echo '  롤백 완료' || echo '  되돌릴 이전 jar 이 없다'"
  printf '\n최근 로그:\n'
  "${SSH[@]}" "sudo journalctl -u $SERVICE --no-pager -n 30 | grep -aE 'ERROR|Caused by|APPLICATION FAILED' | tail -10" || true
  die "배포 실패 (롤백함)"
fi

# ── 6. 확인 ────────────────────────────────────────────────────────────────
say "확인"
TITLE=$(curl -s -m 15 "$URL/" | grep -oE '<title>[^<]*</title>' | head -1 || true)
printf '  %s  %s\n' "$URL" "$TITLE"
"${SSH[@]}" "free -h | awk '/Mem:/{printf \"  메모리 가용 %s / 스왑 사용 \", \$7} /Swap:/{print \$3}'"

printf '\n\033[32m✓ 배포 완료\033[0m\n'
