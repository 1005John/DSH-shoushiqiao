#!/system/bin/sh
# pc.sh —— 访问 PC 文件的统一入口（自动选最快的路径）
#
# 用法：
#   sh /sdcard/Download/pc.sh get  "/search?name=xxx&max=5"
#   sh /sdcard/Download/pc.sh get  "/file?path=D%3A%5Cxxx.pdf" -o /sdcard/Download/x.pdf
#   sh /sdcard/Download/pc.sh post "/upload?path=D:/x.txt" --data-binary @/sdcard/Download/x.txt
#   sh /sdcard/Download/pc.sh base          # 只打印当前使用的 base
#
# 策略：
#   1. 用缓存的 base（2 秒探活）
#   2. 缓存失效 → 问公网 /health 拿 lan_urls，优先同网段（2 秒探活）
#   3. 都不行 → 用公网
#
# LAN base  形如 http://192.168.x.x:8094   （不带 /pc）
# 公网 base 形如 https://host/pc

TOKEN="YOUR_PC_FILE_TOKEN"
PUBLIC="https://39-106-60-159.sslip.io/pc"
CACHE="/sdcard/Download/.pc_base"
CURL="/system/bin/curl"

my_prefix() {
  IP=$(ip -4 addr 2>/dev/null | grep 'inet ' | grep -v '127.0.0.1' | grep -v 'tun' \
        | head -1 | sed 's/.*inet \([0-9.]*\).*/\1/')
  echo "$IP" | cut -d. -f1-3
}

probe() {
  $CURL -sk -m 2 -H "X-Token: $TOKEN" "$1/health" >/dev/null 2>&1
}

resolve_base() {
  if [ -f "$CACHE" ]; then
    B=$(cat "$CACHE")
    if [ -n "$B" ]; then
      if probe "$B"; then
        echo "$B"
        return 0
      fi
    fi
  fi

  MY=$(my_prefix)
  LAN=$($CURL -sk -m 8 -H "X-Token: $TOKEN" "$PUBLIC/health" 2>/dev/null \
        | grep -o 'http://[0-9.]*:8094' | sort -u)

  # 先试同网段
  for u in $LAN; do
    PRE=$(echo "$u" | sed 's#http://##' | cut -d. -f1-3)
    if [ "$PRE" = "$MY" ]; then
      if probe "$u"; then
        echo "$u" > "$CACHE"
        echo "$u"
        return 0
      fi
    fi
  done

  # 再试任意能通的
  for u in $LAN; do
    if probe "$u"; then
      echo "$u" > "$CACHE"
      echo "$u"
      return 0
    fi
  done

  echo "$PUBLIC" > "$CACHE"
  echo "$PUBLIC"
  return 0
}

ACTION="$1"
shift

if [ "$ACTION" = "base" ]; then
  resolve_base
  exit 0
fi

BASE=$(resolve_base)

if [ "$ACTION" = "get" ]; then
  P="$1"
  shift
  $CURL -sk -m 300 -H "X-Token: $TOKEN" "$@" "$BASE$P"
elif [ "$ACTION" = "post" ]; then
  P="$1"
  shift
  $CURL -sk -m 300 -X POST -H "X-Token: $TOKEN" "$@" "$BASE$P"
else
  echo '{"error":"usage: pc.sh get|post|base ..."}'
  exit 2
fi
