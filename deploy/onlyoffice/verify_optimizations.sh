#!/bin/bash
# 检查在线文档"省流量"这套优化是否都生效（部署完跑一遍即可）
#   bash verify_optimizations.sh
# 可用环境变量覆盖：BASE（对外地址）、PAN（wf-pan 客户端端口）、CONTAINER
BASE=${BASE:-https://pan.wildfirechat.net}
PAN=${PAN:-http://127.0.0.1:8083}
CONTAINER=${CONTAINER:-wf-docs}

pass() { printf "  \033[32m✓\033[0m %s\n" "$1"; }
fail() { printf "  \033[31m✗\033[0m %s\n" "$1"; FAILED=1; }

FAILED=0
echo "=== 在线文档省流量优化自检（$BASE） ==="

VER=$(curl -s "$BASE/docs/web-apps/apps/api/documents/api.js" | grep -oE '9\.[0-9.]+-[0-9a-f]{16,}' | head -1)
if [ -n "$VER" ]; then pass "ONLYOFFICE 版本 $VER"; else fail "取不到 ONLYOFFICE 版本（/docs/ 不可达？）"; fi

if [ -n "$VER" ]; then
  # 1. Service Worker 补丁：大文件也进缓存
  if curl -s "$BASE/docs/$VER/document_editor_service_worker.js" | grep -q wf-patch; then
    pass "Service Worker 已打补丁（sdkjs/字体等大文件一律缓存）"
  else
    fail "Service Worker 还是官方版本（大文件会被跳过，每次打开都重下）"
  fi

  # 2. 字体子集化
  SZ=$(curl -s -o /dev/null -w '%{size_download}' -H 'Accept-Encoding: gzip' "$BASE/docs/$VER/fonts/217")
  if [ "${SZ:-0}" -lt 6000000 ]; then
    pass "fonts/217 子集化生效：$SZ B（原始 9178672 B，约 -$(python3 -c "print(int((1-$SZ/9178672)*100))" 2>/dev/null || echo 52)%)"
  else
    fail "fonts/217 还是原字体：$SZ B"
  fi

  # 3. 字体表同步（Mono/Sharp 指到 face 0）
  if curl -s "$BASE/docs/$VER/sdkjs/common/AllFonts.js" | grep -q '"WenQuanYi Zen Hei Mono",217,0'; then
    pass "AllFonts.js 已同步（Mono/Sharp → face 0）"
  else
    fail "AllFonts.js 未同步（子集字体只剩 face 0，会缺字）"
  fi
fi

# 4. 只读 PDF 预览
for u in /doc/ /doc/open /doc/preview; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$PAN$u")
  if [ "$CODE" = "200" ]; then pass "$u 200"; else fail "$u 返回 $CODE"; fi
done
if curl -s "$PAN/doc/preview" | grep -q preview-frame; then pass "PDF 预览页已部署"; else fail "PDF 预览页缺失"; fi
if curl -s "$PAN/doc/open" | grep -q isMobileClient; then pass "手机端一律走预览的逻辑已部署"; else fail "open.html 还是旧版（手机端按链接打开仍会加载编辑器）"; fi
if curl -s "$PAN/doc/app.js" | grep -q ensureSession; then pass "文档页会话刷新已部署（避免旧会话导致无权限）"; else fail "doc-web/app.js 缺会话刷新"; fi

CODE=$(curl -s -o /dev/null -w '%{http_code}' "$PAN/doc/preview.pdf?f=1&v=1&e=1&s=x")
if [ "$CODE" != "200" ]; then pass "preview.pdf 对无效链接返回 $CODE（页面会退回编辑器）"; else fail "preview.pdf 对无效链接仍返回 200"; fi

# 5. ONLYOFFICE 容器
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "^$CONTAINER$"; then
  curl -sf -o /dev/null "http://127.0.0.1:8089/healthcheck" && pass "ONLYOFFICE 健康检查通过" || fail "ONLYOFFICE 健康检查失败"
else
  echo "  · 跳过容器检查（本机没有 docker 或容器名不是 $CONTAINER）"
fi

echo
if [ "$FAILED" = "0" ]; then
  echo "全部通过 ✅"
else
  echo "有未通过项 ❌（见上面 ✗）"
fi
exit $FAILED
