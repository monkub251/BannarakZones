#!/usr/bin/env bash
# อัปเดตปลั๊กอิน BannarakZones เป็นตัวล่าสุด แล้วรีสตาร์ตเซิร์ฟ
#
#   bash update.sh
set -Eeuo pipefail

MC_USER=minecraft
MC_DIR=/opt/minecraft
REPO=monkub251/BannarakZones
JAR="$MC_DIR/plugins/BannarakZones.jar"

RED=$'\e[31m'; GRN=$'\e[32m'; RST=$'\e[0m'
say() { echo "${GRN}==>${RST} $*"; }
die() { echo "${RED}ผิดพลาด:${RST} $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "ต้องรันด้วย sudo"
[[ -d $MC_DIR/plugins ]] || die "ยังไม่มี $MC_DIR/plugins — ติดตั้งเซิร์ฟก่อน"
command -v jq >/dev/null || { apt-get update -qq; apt-get install -y -qq jq; }

now=$([[ -f $JAR ]] && unzip -p "$JAR" plugin.yml 2>/dev/null | awk '/^version:/{print $2}' || echo "ไม่มี")
say "ตอนนี้ใช้ $now"

# เรียงตามเลขเวอร์ชัน ไม่ใช่เวลาอัปโหลด
URL=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/tags/latest" \
  | jq -r '[.assets[] | select(.name|test("^BannarakZones-[0-9.]+\\.jar$"))]
           | map(. + {v: (.name | capture("-(?<a>[0-9]+)\\.(?<b>[0-9]+)\\.(?<c>[0-9]+)\\.jar$")
                               | [(.a|tonumber),(.b|tonumber),(.c|tonumber)])})
           | sort_by(.v) | last | .browser_download_url // empty')
[[ -n $URL ]] || die "หาไฟล์ปลั๊กอินบน GitHub ไม่เจอ"
say "ตัวล่าสุดคือ $(basename "$URL")"

tmp=$(mktemp /tmp/bz-XXXXXX.jar)
curl -fSL --retry 3 -o "$tmp" "$URL"
unzip -l "$tmp" >/dev/null 2>&1 || { rm -f "$tmp"; die "ไฟล์ที่โหลดมาไม่ใช่ .jar ที่ใช้ได้"; }

WAS_RUNNING=no
if systemctl is-active --quiet minecraft; then
  WAS_RUNNING=yes
  # Same guard as op.sh: never leave the server down because this script died.
  trap 'if [[ $WAS_RUNNING == yes ]] && ! systemctl is-active --quiet minecraft; then
          echo "เปิดเซิร์ฟกลับ"; systemctl start minecraft; fi' EXIT HUP INT TERM
  say "ปิดเซิร์ฟก่อนเปลี่ยนไฟล์"
  systemctl stop minecraft
fi

# เก็บตัวเก่าไว้หนึ่งชุด เผื่อตัวใหม่มีปัญหาแล้วอยากถอยกลับ
[[ -f $JAR ]] && cp -f "$JAR" "$JAR.bak"
mv "$tmp" "$JAR"
chown "${MC_USER}:${MC_USER}" "$JAR"
chmod 644 "$JAR"
say "เปลี่ยนเป็น $(unzip -p "$JAR" plugin.yml | awk '/^version:/{print $2}') แล้ว"

if [[ $WAS_RUNNING == yes ]]; then
  say "เปิดเซิร์ฟกลับ"
  systemctl start minecraft
else
  echo "    เปิดเซิร์ฟด้วย  systemctl start minecraft"
fi
echo "    ถ้าตัวใหม่มีปัญหา ตัวเก่าอยู่ที่  $JAR.bak"
