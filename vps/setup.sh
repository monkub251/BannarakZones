#!/usr/bin/env bash
#
# BANNARAKX - ติดตั้งเซิร์ฟ Minecraft บน VPS Ubuntu/Debian ใหม่เอี่ยม
#
#   sudo bash setup.sh
#
# ทำอะไรบ้าง: Java 25 · Paper 26.3 · ปลั๊กอิน BannarakZones · Geyser + Floodgate
#              ไฟร์วอลล์ · รันอัตโนมัติตอนบูต · รีสตาร์ตเองถ้าล่ม · สำรองข้อมูลทุกวัน
#
# สคริปต์นี้ "หยุดทันทีเมื่อมีอะไรผิด" ไม่เดินต่อแบบครึ่ง ๆ กลาง ๆ

set -Eeuo pipefail

MC_USER=minecraft
MC_DIR=/opt/minecraft
MC_VERSION=26.3
PLUGIN_REPO=monkub251/BannarakZones

RED=$'\e[31m'; GRN=$'\e[32m'; YEL=$'\e[33m'; RST=$'\e[0m'
say()  { echo "${GRN}==>${RST} $*"; }
warn() { echo "${YEL}!!${RST}  $*"; }
die()  { echo "${RED}ผิดพลาด:${RST} $*" >&2; exit 1; }

trap 'die "หยุดที่บรรทัด $LINENO — อ่านข้อความด้านบน แล้วส่งมาถามได้"' ERR

[[ $EUID -eq 0 ]] || die "ต้องรันด้วย sudo:  sudo bash setup.sh"
command -v apt-get >/dev/null || die "สคริปต์นี้ใช้กับ Ubuntu หรือ Debian เท่านั้น"

# ---------------------------------------------------------------- แรม
TOTAL_MB=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
# เหลือไว้ให้ระบบ 1GB หรือ 25% แล้วแต่ว่าอันไหนมากกว่า
RESERVE=$(( TOTAL_MB / 4 )); (( RESERVE < 1024 )) && RESERVE=1024
HEAP=$(( TOTAL_MB - RESERVE ))
(( HEAP < 1536 )) && die "แรมน้อยเกินไป (${TOTAL_MB}MB) — เซิร์ฟที่มีปลั๊กอินแบบนี้ต้องใช้แพ็ก 4GB ขึ้นไป"
say "แรมเครื่อง ${TOTAL_MB}MB → ให้เซิร์ฟใช้ ${HEAP}MB เหลือให้ระบบ ${RESERVE}MB"

# ---------------------------------------------------------------- ของพื้นฐาน
say "ติดตั้งของพื้นฐาน"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl wget gnupg ca-certificates ufw screen jq unzip cron >/dev/null

# ---------------------------------------------------------------- Java 25
install_java_apt() {
  install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  chmod 0644 /etc/apt/keyrings/adoptium.gpg
  local codename
  codename=$(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release)
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${codename} main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  apt-get install -y -qq temurin-25-jdk >/dev/null
}

install_java_tarball() {
  warn "ลงผ่าน apt ไม่ได้ ใช้วิธีโหลดไฟล์ตรงแทน"
  local arch url
  case "$(uname -m)" in
    x86_64)  arch=x64 ;;
    aarch64) arch=aarch64 ;;
    *) die "ไม่รองรับซีพียู $(uname -m)" ;;
  esac
  url="https://api.adoptium.net/v3/binary/latest/25/ga/linux/${arch}/jdk/hotspot/normal/eclipse"
  mkdir -p /opt/java && cd /opt/java
  curl -fSL --retry 3 -o jdk.tar.gz "$url" || die "โหลด Java 25 ไม่สำเร็จ"
  tar xzf jdk.tar.gz && rm jdk.tar.gz
  local home
  home=$(find /opt/java -maxdepth 1 -type d -name 'jdk-25*' | head -1)
  [[ -n $home ]] || die "แตกไฟล์ Java แล้วหาโฟลเดอร์ไม่เจอ"
  ln -sf "$home/bin/java" /usr/local/bin/java
}

if java -version 2>&1 | grep -q '"25'; then
  say "มี Java 25 อยู่แล้ว"
else
  say "ติดตั้ง Java 25"
  install_java_apt || install_java_tarball
fi

JAVA_V=$(java -version 2>&1 | head -1)
echo "$JAVA_V" | grep -q '"25' \
  || die "ต้องการ Java 25 แต่ได้ $JAVA_V — Paper ${MC_VERSION} รันไม่ได้"
say "Java พร้อม: $JAVA_V"

# ---------------------------------------------------------------- ผู้ใช้
id -u "$MC_USER" &>/dev/null || { say "สร้างผู้ใช้ $MC_USER"; useradd -r -m -d "$MC_DIR" -s /bin/bash "$MC_USER"; }
mkdir -p "$MC_DIR"/{plugins,backups}
chown -R "$MC_USER:$MC_USER" "$MC_DIR"

# ---------------------------------------------------------------- Paper
fetch_paper() {
  local api="https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}"
  local builds build name url
  builds=$(curl -fsSL --retry 3 "$api" 2>/dev/null) || return 1
  build=$(echo "$builds" | jq -r '.builds[-1] // empty') || return 1
  [[ -n $build ]] || return 1
  name=$(curl -fsSL "${api}/builds/${build}" | jq -r '.downloads.application.name // empty')
  [[ -n $name ]] || return 1
  url="${api}/builds/${build}/downloads/${name}"
  say "เจอ Paper ${MC_VERSION} build ${build}"
  curl -fSL --retry 3 -o "$MC_DIR/paper.jar" "$url"
}

if [[ -s $MC_DIR/paper.jar ]]; then
  say "มี paper.jar อยู่แล้ว ข้ามการโหลด"
else
  say "โหลด Paper ${MC_VERSION}"
  if ! fetch_paper; then
    cat <<MSG
${RED}โหลด Paper อัตโนมัติไม่สำเร็จ${RST}

เว็บ Paper อาจเปลี่ยนรูปแบบลิงก์ ทำเองได้แบบนี้:
  1. เปิด https://papermc.io/downloads/paper ในเบราว์เซอร์
  2. เลือกเวอร์ชัน ${MC_VERSION} แล้วโหลดไฟล์ .jar
  3. อัปโหลดขึ้น VPS ไว้ที่  ${MC_DIR}/paper.jar
  4. รันสคริปต์นี้ใหม่อีกครั้ง
MSG
    exit 1
  fi
fi
# เช็กว่าที่โหลดมาเป็น jar จริง ไม่ใช่หน้า error
unzip -l "$MC_DIR/paper.jar" >/dev/null 2>&1 || die "paper.jar ที่โหลดมาเสียหาย ลบทิ้งแล้วรันใหม่"
say "Paper พร้อม ($(du -h "$MC_DIR/paper.jar" | cut -f1))"

# ---------------------------------------------------------------- ปลั๊กอินของเรา
say "โหลดปลั๊กอิน BannarakZones ตัวล่าสุด"
PLUGIN_URL=$(curl -fsSL "https://api.github.com/repos/${PLUGIN_REPO}/releases/tags/latest" \
  | jq -r '[.assets[] | select(.name|test("^BannarakZones-[0-9.]+\\.jar$"))]
           | sort_by(.updated_at) | last | .browser_download_url // empty') || true
if [[ -n ${PLUGIN_URL:-} ]]; then
  curl -fSL --retry 3 -o "$MC_DIR/plugins/BannarakZones.jar" "$PLUGIN_URL"
  say "ได้ปลั๊กอินแล้ว: $(basename "$PLUGIN_URL")"
else
  warn "หาไฟล์ปลั๊กอินไม่เจอ ข้ามไปก่อน อัปโหลดเองทีหลังได้"
fi

# ---------------------------------------------------------------- Geyser + Floodgate
get_geyser() {
  local proj=$1 out=$2
  local url="https://download.geysermc.org/v2/projects/${proj}/versions/latest/builds/latest/downloads/spigot"
  curl -fSL --retry 3 -o "$out" "$url" && unzip -l "$out" >/dev/null 2>&1
}
say "โหลด Geyser และ Floodgate (ให้คนเล่นมือถือเข้าได้)"
get_geyser geyser    "$MC_DIR/plugins/Geyser-Spigot.jar" \
  || warn "โหลด Geyser ไม่สำเร็จ โหลดเองจาก https://geysermc.org/download"
get_geyser floodgate "$MC_DIR/plugins/floodgate-spigot.jar" \
  || warn "โหลด Floodgate ไม่สำเร็จ โหลดเองจาก https://geysermc.org/download"

# ---------------------------------------------------------------- Geyser config
# Geyser's own default is "nethernet" only, which shuts out Bedrock clients that
# still speak RakNet. Writing the config up front also avoids the mistake that
# broke the home server: an invalid "transport" value makes Geyser disable itself.
GEY_DIR="$MC_DIR/plugins/Geyser-Spigot"
if [[ -f $GEY_DIR/config.yml ]]; then
  say "Geyser มี config อยู่แล้ว ไม่แตะ"
else
  say "เขียน config ของ Geyser (รับทั้ง Bedrock แบบเก่าและใหม่)"
  mkdir -p "$GEY_DIR"
  cat > "$GEY_DIR/config.yml" <<'GEY'
# ตั้งค่าโดยสคริปต์ติดตั้ง BANNARAKX
bedrock:
  address: 0.0.0.0
  port: 19132
  # โหมด both ต้องใช้คนละพอร์ต: RakNet ใช้ port, NetherNet ใช้ webrtc-port
  webrtc-port: 19133
  # รับได้แค่ "nethernet" หรือ "both" เท่านั้น ใส่ค่าอื่น Geyser จะปิดตัวเอง
  transport: both
  clone-remote-port: false
  signaling:
    # builtin = Geyser รันเอง ไม่พึ่งบริการข้างนอก ใช้ได้เพราะเครื่องนี้มีไอพีจริง
    mode: builtin
    advertise-addresses: []
    stun-discovery: true

java:
  auth-type: floodgate

motd:
  primary-motd: BANNARAKX
  secondary-motd: Java + Bedrock
  passthrough-motd: true
  max-players: 100
  passthrough-player-counts: true

gameplay:
  server-name: BANNARAKX
  show-coordinates: true

default-locale: system
config-version: 11
GEY
  chown -R "$MC_USER:$MC_USER" "$GEY_DIR"
fi

# ---------------------------------------------------------------- ค่าตั้งต้น
[[ -f $MC_DIR/eula.txt ]] || echo "eula=true" > "$MC_DIR/eula.txt"

if [[ ! -f $MC_DIR/server.properties ]]; then
  say "เขียน server.properties"
  cat > "$MC_DIR/server.properties" <<'PROPS'
server-port=25565
online-mode=false
enforce-secure-profile=false
max-players=100
view-distance=8
simulation-distance=6
spawn-protection=0
enable-command-block=true
motd=§6BANNARAKX §7| §fSurvival 26.3
PROPS
fi

if [[ ! -f $MC_DIR/bukkit.yml ]]; then
  cat > "$MC_DIR/bukkit.yml" <<'BUK'
settings:
  update-folder: update
  shutdown-message: "เซิร์ฟกำลังรีสตาร์ต เดี๋ยวกลับมา"
BUK
fi
mkdir -p "$MC_DIR/plugins/update"

# ---------------------------------------------------------------- ตัวเปิดเซิร์ฟ
say "เขียนตัวเปิดเซิร์ฟ (แรม ${HEAP}MB + Aikar's flags)"
cat > "$MC_DIR/start.sh" <<EOF
#!/usr/bin/env bash
cd "$MC_DIR"
exec java -Xms${HEAP}M -Xmx${HEAP}M \\
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \\
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC \\
  -XX:+AlwaysPreTouch -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \\
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \\
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 \\
  -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \\
  -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true \\
  -jar paper.jar --nogui
EOF
chmod +x "$MC_DIR/start.sh"
chown -R "$MC_USER:$MC_USER" "$MC_DIR"

# ---------------------------------------------------------------- systemd
say "ตั้งให้เปิดเองตอนบูต และรีสตาร์ตเองถ้าล่ม"
cat > /etc/systemd/system/minecraft.service <<EOF
[Unit]
Description=BANNARAKX Minecraft server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${MC_USER}
WorkingDirectory=${MC_DIR}
ExecStart=${MC_DIR}/start.sh
Restart=always
RestartSec=15
SuccessExitStatus=0 143
# Paper รับ SIGTERM แล้วเซฟโลกกับปิดให้เอง ไม่ต้องยัดคำสั่ง stop
KillSignal=SIGTERM
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable minecraft >/dev/null

# ---------------------------------------------------------------- ไฟร์วอลล์
say "เปิดพอร์ตที่ต้องใช้"
ufw --force reset >/dev/null 2>&1 || true
ufw default deny incoming  >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp     comment 'SSH'        >/dev/null
ufw allow 25565/tcp  comment 'Java'       >/dev/null
ufw allow 19132/udp  comment 'Bedrock'    >/dev/null
ufw allow 19133/udp  comment 'NetherNet'  >/dev/null
ufw allow 8765/tcp   comment 'แผงเว็บ'     >/dev/null
ufw --force enable   >/dev/null
say "พอร์ตที่เปิด: 22 · 25565/tcp · 19132/udp · 19133/udp · 8765/tcp"

# ---------------------------------------------------------------- สำรองข้อมูล
say "ตั้งสำรองข้อมูลทุกวันตี 4 เก็บย้อนหลัง 7 วัน"
cat > /usr/local/bin/mc-backup <<EOF
#!/usr/bin/env bash
set -eu
cd "$MC_DIR"
stamp=\$(date +%Y%m%d-%H%M)
tar czf "backups/world-\$stamp.tar.gz" world plugins 2>/dev/null || true
find backups -name 'world-*.tar.gz' -mtime +7 -delete
EOF
chmod +x /usr/local/bin/mc-backup
echo "0 4 * * * ${MC_USER} /usr/local/bin/mc-backup" > /etc/cron.d/mc-backup

# ---------------------------------------------------------------- เริ่ม
say "เปิดเซิร์ฟ"
systemctl start minecraft
sleep 12

IP=$(curl -fsS --max-time 10 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')

echo
echo "────────────────────────────────────────────"
if systemctl is-active --quiet minecraft; then
  echo "${GRN}เซิร์ฟกำลังทำงาน${RST}"
else
  echo "${RED}เซิร์ฟยังไม่ขึ้น${RST}  ดูสาเหตุ:  journalctl -u minecraft -n 50 --no-pager"
fi
echo
echo "  ที่อยู่เซิร์ฟ (Java)     ${IP}"
echo "  ที่อยู่เซิร์ฟ (มือถือ)    ${IP} พอร์ต 19132"
echo "  แผงเว็บ                http://${IP}:8765"
echo
echo "คำสั่งที่ใช้บ่อย"
echo "  ดูสถานะ      systemctl status minecraft"
echo "  ดู log สด     journalctl -u minecraft -f"
echo "  รีสตาร์ต      systemctl restart minecraft"
echo "  ปิด          systemctl stop minecraft"
echo "  พิมพ์คำสั่งในเซิร์ฟ  ใช้ /admin ในเกม หรือแผงเว็บที่พอร์ต 8765"
echo
echo "${YEL}ยังต้องทำต่อ${RST}"
echo "  1. อัปโหลดปลั๊กอินที่เหลือ (AuthMe, LuckPerms, EssentialsX ฯลฯ) ไปที่ ${MC_DIR}/plugins/"
echo "  2. อัปโหลดโลกเดิมถ้าจะเอามาใช้ ไปที่ ${MC_DIR}/world/"
echo "  3. แก้ server.url ในบอทดิสคอร์ด เป็น http://${IP}:8765"
echo "  4. รีสตาร์ต:  systemctl restart minecraft"
echo "────────────────────────────────────────────"
