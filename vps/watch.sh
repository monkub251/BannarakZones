#!/usr/bin/env bash
# ติดตั้งตัวเฝ้าเซิร์ฟ: เช็กทุก 5 นาที ถ้าเซิร์ฟดับโดยไม่ได้ตั้งใจ เปิดกลับให้เอง
#
#   bash watch.sh
#
# รันซ้ำได้ ไม่พัง แค่เขียนทับของเดิม
set -Eeuo pipefail

MC_DIR=/opt/minecraft
GRN=$'\e[32m'; RED=$'\e[31m'; RST=$'\e[0m'
say() { echo "${GRN}==>${RST} $*"; }
die() { echo "${RED}ผิดพลาด:${RST} $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "ต้องรันด้วย sudo"
[[ -d $MC_DIR ]]  || die "ยังไม่มี $MC_DIR"

cat > /usr/local/bin/mc-watch <<'WATCH'
#!/usr/bin/env bash
# เปิดเซิร์ฟกลับถ้ามันดับเอง และบอกในดิสคอร์ด
#
# ไม่ยุ่งตอนกำลังอัปเดต: สคริปต์ที่ตั้งใจปิดเซิร์ฟจะสร้างไฟล์ .maintenance ไว้
MC_DIR=/opt/minecraft
STATE=$MC_DIR/.watch-state

[[ -f $MC_DIR/.maintenance ]] && exit 0

state=$(systemctl is-active minecraft 2>/dev/null || true)
case "$state" in
  active|activating) echo up > "$STATE"; exit 0 ;;
esac

# ถูกสั่งปิดด้วยมือก็ไม่ไปยุ่ง เปิดเฉพาะที่ตั้งใจให้เปิดตลอด
systemctl is-enabled --quiet minecraft 2>/dev/null || exit 0

was=$(cat "$STATE" 2>/dev/null || echo up)
systemctl start minecraft
echo down > "$STATE"

# บอกครั้งเดียวตอนเพิ่งล่ม ไม่ใช่ทุก 5 นาที
[[ $was == down ]] && exit 0

hook=$(grep -A3 '^ticket:' "$MC_DIR/plugins/BannarakZones/config.yml" 2>/dev/null \
       | grep 'discord-webhook:' | sed 's/.*discord-webhook:[[:space:]]*//; s/^"//; s/"$//' | tr -d "'")
[[ $hook == https://* ]] || exit 0

when=$(date '+%d/%m %H:%M')
curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
  -d "{\"username\":\"เฝ้าเซิร์ฟ\",\"content\":\"⚠️ เซิร์ฟดับเมื่อ $when — เปิดกลับให้แล้ว\"}" \
  "$hook" >/dev/null 2>&1 || true
WATCH
chmod +x /usr/local/bin/mc-watch

echo "*/5 * * * * root /usr/local/bin/mc-watch" > /etc/cron.d/mc-watch
chmod 644 /etc/cron.d/mc-watch
rm -f "$MC_DIR/.maintenance"

say "ตัวเฝ้าทำงานแล้ว เช็กทุก 5 นาที"
echo "    ดับเอง → เปิดกลับให้ + บอกในดิสคอร์ด (ถ้าตั้ง webhook ไว้)"
echo "    สั่งปิดเอง (systemctl disable) → ไม่ไปยุ่ง"
echo "    ตอนอัปเดต → ไม่ไปยุ่ง เพราะมีไฟล์ .maintenance กันไว้"
echo
echo "  ทดสอบเลยก็ได้:  systemctl stop minecraft   แล้วรอ 5 นาที"
