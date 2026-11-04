#!/usr/bin/env bash
# ตั้งคนให้เป็นแอดมิน (op) บนเซิร์ฟที่รันด้วย systemd
#
# เซิร์ฟบน VPS ไม่มีหน้าต่างคอนโซลให้พิมพ์ op ได้ สคริปต์นี้เลยเขียนลง
# ops.json ให้ตรง ๆ แล้วเปิดเซิร์ฟใหม่
#
#   bash op.sh Monkub251
#   bash op.sh Monkub251 1234abcd-...   ใส่ UUID เองถ้าหาไม่เจอ
set -euo pipefail

MC_DIR=/opt/minecraft
MC_USER=minecraft
OPS="$MC_DIR/ops.json"

red()  { echo -e "\033[31m$*\033[0m"; }
say()  { echo -e "\033[32m==>\033[0m $*"; }

NAME=${1:-}
UUID=${2:-}

if [[ -z $NAME ]]; then
  red "ต้องใส่ชื่อในเกมด้วย  เช่น  bash op.sh Monkub251"
  exit 1
fi
if [[ ! -d $MC_DIR ]]; then
  red "ยังไม่มี $MC_DIR  ติดตั้งเซิร์ฟก่อน"
  exit 1
fi
command -v jq >/dev/null || { apt-get update -qq && apt-get install -y -qq jq; }

# ---- หา UUID ----
# เซิร์ฟที่ตั้ง online-mode=false (ต้องตั้งแบบนี้ถ้าจะให้ Geyser ทำงาน) ไม่ได้ใช้
# UUID ของ Mojang แต่สร้างเอาเองจากชื่อผู้เล่น ถ้าไปเอาของ Mojang มาใส่ ops.json
# จะชี้ไปที่คนที่ไม่มีอยู่จริงบนเซิร์ฟนี้ แล้ว op จะไม่มีผลอะไรเลย
ONLINE=$(grep -E '^online-mode=' "$MC_DIR/server.properties" 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')
ONLINE=${ONLINE:-true}

offline_uuid() {
  command -v python3 >/dev/null || return 1
  python3 - "$1" <<'PYEOF'
import hashlib, sys, uuid
d = bytearray(hashlib.md5(("OfflinePlayer:" + sys.argv[1]).encode("utf-8")).digest())
d[6] = (d[6] & 0x0f) | 0x30
d[8] = (d[8] & 0x3f) | 0x80
print(uuid.UUID(bytes=bytes(d)))
PYEOF
}

if [[ -z $UUID ]]; then
  if [[ $ONLINE == false ]]; then
    say "เซิร์ฟเป็น online-mode=false — คิด UUID จากชื่อเอง ไม่ต้องถาม Mojang"
    UUID=$(offline_uuid "$NAME" || true)
    if [[ -z ${UUID:-} ]]; then
      red "คิด UUID ไม่ได้ (ไม่มี python3)"
      echo "  ติดตั้งด้วย  apt install -y python3  แล้วรันใหม่"
      exit 1
    fi
  else
    say "ถาม Mojang ว่า $NAME มี UUID อะไร"
    raw=$(curl -fsS --max-time 15 "https://api.mojang.com/users/profiles/minecraft/${NAME}" 2>/dev/null || true)
    id=$(echo "${raw:-}" | jq -r '.id // empty' 2>/dev/null || true)
    if [[ -n $id && ${#id} -eq 32 ]]; then
      UUID="${id:0:8}-${id:8:4}-${id:12:4}-${id:16:4}-${id:20:12}"
      NAME=$(echo "$raw" | jq -r '.name // empty')
    else
      red "หา UUID ไม่ได้"
      echo "  เปิด https://namemc.com/profile/${NAME} เอา UUID มาใส่เอง แล้วสั่ง"
      echo "  bash op.sh ${NAME} <UUID ที่มีขีด>"
      exit 1
    fi
  fi
fi
say "$NAME  →  $UUID"

# ---- หยุดเซิร์ฟก่อนแก้ ----
# เซิร์ฟเขียน ops.json ทับตอนปิด ถ้าแก้ตอนมันรันอยู่ของที่เขียนจะหาย
WAS_RUNNING=no
if systemctl is-active --quiet minecraft; then
  WAS_RUNNING=yes
  # Whatever happens next — an error, a closed terminal, Ctrl+C — the server
  # must not be left switched off. Leaving it down is far worse than failing
  # to set a rank.
  trap 'if [[ $WAS_RUNNING == yes ]] && ! systemctl is-active --quiet minecraft; then
          echo "เปิดเซิร์ฟกลับ"; systemctl start minecraft; fi' EXIT HUP INT TERM
  say "ปิดเซิร์ฟชั่วคราว"
  systemctl stop minecraft
fi

# ---- เขียน ops.json ----
[[ -s $OPS ]] || echo '[]' > "$OPS"
if ! jq empty "$OPS" 2>/dev/null; then
  say "ops.json เดิมเสีย เขียนใหม่"
  echo '[]' > "$OPS"
fi

tmp=$(mktemp)
jq --arg u "$UUID" --arg n "$NAME" \
   '[ .[] | select((.uuid // "") != $u) ]
    + [ { uuid: $u, name: $n, level: 4, bypassesPlayerLimit: true } ]' \
   "$OPS" > "$tmp"
mv "$tmp" "$OPS"
chown "${MC_USER}:${MC_USER}" "$OPS"
chmod 644 "$OPS"

say "ตอนนี้เป็นแอดมินแล้ว:"
jq -r '.[] | "    " + .name' "$OPS"

if [[ $WAS_RUNNING == yes ]]; then
  say "เปิดเซิร์ฟกลับ"
  systemctl start minecraft
else
  echo "    เปิดเซิร์ฟด้วย  systemctl start minecraft"
fi
