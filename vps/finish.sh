#!/usr/bin/env bash
# เก็บงานที่เหลือให้จบ: ปลั๊กอินที่เพิ่งอัปมา · รีสตาร์ตอัตโนมัติ · Paper ตัวใหม่ · บอทดิสคอร์ด
#
#   bash finish.sh
#
# รันซ้ำได้ ไม่พัง ข้ามขั้นที่ทำไปแล้วเอง
set -Eeuo pipefail

MC_USER=minecraft
MC_DIR=/opt/minecraft
BOT_DIR=/opt/discordbot
MC_VERSION=26.3
UA="BannarakZones-setup/1.0 (+https://github.com/monkub251/BannarakZones)"

RED=$'\e[31m'; GRN=$'\e[32m'; YEL=$'\e[33m'; RST=$'\e[0m'
say()  { echo "${GRN}==>${RST} $*"; }
warn() { echo "${YEL}!!${RST}  $*"; }
die()  { echo "${RED}ผิดพลาด:${RST} $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "ต้องรันด้วย sudo"
[[ -d $MC_DIR ]]  || die "ยังไม่มี $MC_DIR"
command -v jq >/dev/null || { apt-get update -qq; apt-get install -y -qq jq; }

LEFT=()

# ---------------------------------------------------------------- 1 ปลั๊กอิน
say "ตรวจปลั๊กอินใน $MC_DIR/plugins"
chown -R "$MC_USER:$MC_USER" "$MC_DIR/plugins"
for want in AuthMe LuckPerms; do
  if ls "$MC_DIR"/plugins/*"$want"*.jar >/dev/null 2>&1; then
    echo "    มี $want แล้ว"
  else
    warn "ยังไม่มี $want"
    LEFT+=("อัป $want ขึ้นมาที่ $MC_DIR/plugins/")
  fi
done

# ---------------------------------------------------------------- 2 รีสตาร์ตอัตโนมัติ
CFG="$MC_DIR/plugins/BannarakZones/config.yml"
if [[ -f $CFG ]]; then
  if grep -q '^restart:' "$CFG"; then
    # systemd เปิดเซิร์ฟกลับให้เองอยู่แล้ว เปิดรีสตาร์ตรายวันได้ปลอดภัย
    if grep -A2 '^restart:' "$CFG" | grep -q 'enabled: *false'; then
      sed -i '/^restart:/,/^[^ ]/ s/enabled: *false/enabled: true/' "$CFG"
      say "เปิดรีสตาร์ตอัตโนมัติ 04:00 ทุกวันแล้ว"
    else
      say "รีสตาร์ตอัตโนมัติเปิดอยู่แล้ว"
    fi
  else
    warn "config.yml ยังไม่มีส่วน restart: (ปลั๊กอินเก่ากว่า 2.15.0)"
    LEFT+=("อัปปลั๊กอินเป็นตัวล่าสุดแล้วรัน finish.sh ใหม่")
  fi
else
  warn "ยังไม่มี $CFG — เซิร์ฟยังไม่เคยรันจนสร้างคอนฟิก"
fi

# ------------------------------------------------- ชื่อผู้เล่นฝั่ง Bedrock
# Floodgate เติมจุดหน้าชื่อคนเล่น Bedrock กันชนกับชื่อฝั่ง Java แต่ AuthMe
# ยอมรับแค่ a-z A-Z 0-9 _ จุดเลยกลายเป็นตัวต้องห้าม คนเล่นมือถือเข้าไม่ได้เลย
# และชื่อ Xbox มีเว้นวรรคได้ ซึ่งก็ผิดกฎเดียวกัน
AUTHME_CFG="$MC_DIR/plugins/AuthMe/config.yml"
FLOOD_CFG="$MC_DIR/plugins/floodgate/config.yml"

if [[ -f $AUTHME_CFG ]]; then
  if grep -q "allowedNicknameCharacters: *'\[a-zA-Z0-9_\]\*'" "$AUTHME_CFG"; then
    sed -i "s/^\( *\)allowedNicknameCharacters:.*/\1allowedNicknameCharacters: '[a-zA-Z0-9_.]*'/" "$AUTHME_CFG"
    say "ให้ AuthMe ยอมรับจุดหน้าชื่อคนเล่น Bedrock แล้ว"
  else
    say "AuthMe รับชื่อแบบ Bedrock ได้อยู่แล้ว"
  fi
else
  warn "ยังไม่มี $AUTHME_CFG — เปิดเซิร์ฟสักครั้งก่อนแล้วรัน finish.sh ใหม่"
  LEFT+=("รัน finish.sh อีกครั้งหลังเซิร์ฟบูตแล้ว เพื่อตั้งชื่อผู้เล่นฝั่ง Bedrock")
fi

if [[ -f $FLOOD_CFG ]]; then
  if grep -q '^ *replace-spaces: *false' "$FLOOD_CFG"; then
    sed -i "s/^\( *\)replace-spaces:.*/\1replace-spaces: true/" "$FLOOD_CFG"
    say "ให้ Floodgate เปลี่ยนเว้นวรรคในชื่อ Xbox เป็นขีดล่างแล้ว"
  else
    say "Floodgate ตั้งเรื่องเว้นวรรคไว้แล้ว"
  fi
fi

# ---------------------------------------------------------------- 3 Paper
say "ตรวจว่า Paper เป็น build ล่าสุดหรือยัง"
newest_paper_url() {
  curl -fsSL --retry 2 --max-time 30 -A "$UA" \
       "https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds" 2>/dev/null \
  | jq -r '[.. | strings | select(test("^https?://.*\\.jar([?#]|$)"))] | unique | .[]' \
  | awk -F'-' '{n=$NF; sub(/\.jar.*/,"",n); if (n ~ /^[0-9]+$/) print n, $0}' \
  | sort -k1,1nr | head -1 | cut -d' ' -f2-
}
PU=$(newest_paper_url || true)
if [[ -n ${PU:-} ]]; then
  NEW=$(echo "$PU" | sed 's/.*-\([0-9]\{1,\}\)\.jar.*/\1/')
  # เลข build ไม่ได้อยู่ใน version.json ของ Paper (อันนั้นเป็นของ Mojang)
  # จดไว้ข้าง ๆ ตอนโหลดเองดีกว่า จะได้ไม่โหลดซ้ำ 52MB ทุกครั้งที่รัน
  HAVE=$(cat "$MC_DIR/.paper-build" 2>/dev/null || true)
  if [[ -n $HAVE && $HAVE == "$NEW" ]]; then
    say "Paper เป็น build $HAVE อยู่แล้ว"
  else
    say "โหลด Paper build $NEW"
    tmp=$(mktemp /tmp/paper-XXXXXX.jar)
    if curl -fSL --retry 3 -A "$UA" -o "$tmp" "$PU" && unzip -l "$tmp" >/dev/null 2>&1; then
      systemctl is-active --quiet minecraft && { systemctl stop minecraft; STARTED=yes; }
      cp -f "$MC_DIR/paper.jar" "$MC_DIR/paper.jar.bak" 2>/dev/null || true
      mv "$tmp" "$MC_DIR/paper.jar"
      chown "$MC_USER:$MC_USER" "$MC_DIR/paper.jar"
      echo "$NEW" > "$MC_DIR/.paper-build"
      say "เปลี่ยน Paper เป็น build $NEW แล้ว (ตัวเก่าอยู่ที่ paper.jar.bak)"
    else
      rm -f "$tmp"; warn "โหลด Paper ไม่สำเร็จ ใช้ตัวเดิมไปก่อน"
    fi
  fi
else
  warn "ถามเว็บ Paper ไม่สำเร็จ ข้ามไปก่อน"
fi

# ---------------------------------------------------------------- 4 บอทดิสคอร์ด
if [[ -d $BOT_DIR ]]; then
  say "ตั้งบอทดิสคอร์ด"

  need_node() {
    command -v node >/dev/null || return 0
    local v; v=$(node -v | sed 's/^v//;s/\..*//')
    (( v < 22 ))
  }
  if need_node; then
    say "ติดตั้ง Node 22"
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null 2>&1 || true
    apt-get install -y -qq nodejs >/dev/null 2>&1 || true
  fi
  if need_node; then
    warn "ติดตั้ง Node 22 ไม่สำเร็จ ($(command -v node >/dev/null && node -v || echo 'ไม่มี node'))"
    LEFT+=("ติดตั้ง Node 22 เอง แล้วรัน finish.sh ใหม่")
  else
    say "Node $(node -v)"
    chown -R "$MC_USER:$MC_USER" "$BOT_DIR"

    # token เป็นของเจ้าของเซิร์ฟ สคริปต์แค่เช็กว่าใส่แล้วหรือยัง ไม่แสดงค่า
    TOK_OK=no
    if [[ -f $BOT_DIR/config.json ]] && jq -e '(.token // "") | length > 20' "$BOT_DIR/config.json" >/dev/null 2>&1; then
      if ! jq -e '.token | test("PASTE")' "$BOT_DIR/config.json" >/dev/null 2>&1; then TOK_OK=yes; fi
    fi

    # บอทอยู่เครื่องเดียวกับเซิร์ฟแล้ว คุยผ่าน localhost ได้ ไม่ต้องออกเน็ต
    if [[ -f $BOT_DIR/config.json ]]; then
      t=$(mktemp)
      jq '.server.url = "http://127.0.0.1:8765"' "$BOT_DIR/config.json" > "$t" && mv "$t" "$BOT_DIR/config.json"
      chown "$MC_USER:$MC_USER" "$BOT_DIR/config.json"
      chmod 600 "$BOT_DIR/config.json"
    fi

    say "ติดตั้งไลบรารีของบอท"
    ( cd "$BOT_DIR" && sudo -u "$MC_USER" npm install --omit=dev --no-audit --no-fund ) >/dev/null 2>&1 \
      || warn "npm install มีปัญหา ลองรันเองที่ $BOT_DIR"

    cat > /etc/systemd/system/bzbot.service <<EOF
[Unit]
Description=BANNARAKX Discord bot
After=network-online.target minecraft.service
Wants=network-online.target

[Service]
Type=simple
User=${MC_USER}
WorkingDirectory=${BOT_DIR}
ExecStart=/usr/bin/env node src/index.js
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable bzbot >/dev/null 2>&1 || true
    if [[ $TOK_OK == yes ]]; then
      systemctl restart bzbot
      say "บอทเริ่มทำงานแล้ว"
    else
      warn "ยังไม่ได้ใส่ token ของบอทใน $BOT_DIR/config.json"
      LEFT+=("ใส่ token บอทใน $BOT_DIR/config.json แล้วสั่ง  systemctl start bzbot")
    fi
  fi
else
  warn "ยังไม่มี $BOT_DIR"
  LEFT+=("อัปโฟลเดอร์ discordbot ขึ้นมาที่ $BOT_DIR")
fi

# ---------------------------------------------------------------- 5 เปิดเซิร์ฟ
systemctl is-active --quiet minecraft || systemctl start minecraft
sleep 3
systemctl is-active --quiet minecraft && say "เซิร์ฟกำลังทำงาน" || warn "เซิร์ฟยังไม่ขึ้น ดูด้วย journalctl -u minecraft -n 50 --no-pager"

echo
if ((${#LEFT[@]})); then
  echo "${YEL}ยังเหลือ:${RST}"
  for x in "${LEFT[@]}"; do echo "  · $x"; done
else
  echo "${GRN}ครบแล้ว ไม่เหลืออะไร${RST}"
fi
echo
echo "  ดูเซิร์ฟ    journalctl -u minecraft -f"
echo "  ดูบอท      journalctl -u bzbot -f"
echo "  อัปปลั๊กอิน bash update.sh"
