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
if [[ -z $UUID ]]; then
  say "ถาม Mojang ว่า $NAME มี UUID อะไร"
  raw=$(curl -fsS --max-time 15 "https://api.mojang.com/users/profiles/minecraft/${NAME}" 2>/dev/null || true)
  id=$(echo "${raw:-}" | jq -r '.id // empty' 2>/dev/null || true)
  if [[ -n $id && ${#id} -eq 32 ]]; then
    UUID="${id:0:8}-${id:8:4}-${id:12:4}-${id:16:4}-${id:20:12}"
    # ใช้ตัวสะกดชื่อตามที่ Mojang บอก กันพิมพ์ผิดตัวพิมพ์เล็กใหญ่
    NAME=$(echo "$raw" | jq -r '.name // empty')
  else
    red "หา UUID ไม่ได้"
    echo "  เปิด https://namemc.com/profile/${NAME} เอา UUID มาใส่เอง แล้วสั่ง"
    echo "  bash op.sh ${NAME} <UUID ที่มีขีด>"
    exit 1
  fi
fi
say "$NAME  →  $UUID"

# ---- หยุดเซิร์ฟก่อนแก้ ----
# เซิร์ฟเขียน ops.json ทับตอนปิด ถ้าแก้ตอนมันรันอยู่ของที่เขียนจะหาย
WAS_RUNNING=no
if systemctl is-active --quiet minecraft; then
  WAS_RUNNING=yes
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
