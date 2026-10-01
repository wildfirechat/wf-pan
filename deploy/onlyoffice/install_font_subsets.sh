#!/bin/bash
# 在线文档字体瘦身：把最大的中文字体（ONLYOFFICE fonts/217 = 文泉驿正黑，gzip 9.18MB）
# 换成子集化版本（gzip ≈ 4.37MB，-53%），并同步修正 AllFonts.js 的字体表。
#
# 为什么要动 AllFonts.js：子集化后字体文件只剩 face 0，而字体表里
# "WenQuanYi Zen Hei Mono/Sharp" 还指向 face 1/2，客户端会加载失败，所以把这两个也指到 face 0。
#
# 前提：先用 build_font_subsets.py 生成子集，放在 /root/fonts-subset/（217、217.gz）：
#   docker cp wf-docs:/var/www/onlyoffice/documentserver/fonts/217 /root/fonts-src/217
#   docker cp wf-docs:/var/www/onlyoffice/documentserver/fonts/217.gz /root/fonts-src/217.gz
#   /root/fontenv/bin/python build_font_subsets.py --src /root/fonts-src --out /root/fonts-subset --fonts 217
#
# 用法：install_font_subsets.sh [subset_dir]（默认 /root/fonts-subset）；重复执行幂等。
# 回滚：uninstall_font_subsets.sh（或删掉 nginx 里 /__wf_doc_font_217 那段并 reload）
set -e

SUBSET_DIR=${1:-/root/fonts-subset}
FONT_ID=${FONT_ID:-217}
OVERRIDE_DIR=/root/onlyoffice/fonts-override
CONF=/etc/nginx/conf.d/pan.conf
CONTAINER=${CONTAINER:-wf-docs}

[ -f "$SUBSET_DIR/$FONT_ID" ] || { echo "缺少 $SUBSET_DIR/$FONT_ID（先跑 build_font_subsets.py）"; exit 1; }
[ -f "$SUBSET_DIR/$FONT_ID.gz" ] || { echo "缺少 $SUBSET_DIR/$FONT_ID.gz"; exit 1; }

mkdir -p "$OVERRIDE_DIR"
cp -f "$SUBSET_DIR/$FONT_ID" "$OVERRIDE_DIR/$FONT_ID"
cp -f "$SUBSET_DIR/$FONT_ID.gz" "$OVERRIDE_DIR/$FONT_ID.gz"

echo "[1/3] 修正 AllFonts.js：WenQuanYi Zen Hei 三个名字都指到 face 0"
docker cp "$CONTAINER:/var/www/onlyoffice/documentserver/sdkjs/common/AllFonts.js" "$OVERRIDE_DIR/AllFonts.js" >/dev/null
python3 - "$OVERRIDE_DIR/AllFonts.js" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8-sig').read()
for old, new in [
    ('"WenQuanYi Zen Hei Mono",217,1,', '"WenQuanYi Zen Hei Mono",217,0,'),
    ('"WenQuanYi Zen Hei Sharp",217,2,', '"WenQuanYi Zen Hei Sharp",217,0,'),
]:
    if old in s:
        s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('  AllFonts.js ok')
PY
gzip -9 -c "$OVERRIDE_DIR/AllFonts.js" > "$OVERRIDE_DIR/AllFonts.js.gz"

echo "[2/3] 写 nginx 覆盖规则"
if grep -q "__wf_doc_font_${FONT_ID}" "$CONF"; then
    echo "  已存在，跳过"
else
    cp -f "$CONF" "$CONF.bak-font-$(date +%Y%m%d%H%M%S)"
    python3 - "$CONF" "$OVERRIDE_DIR" "$FONT_ID" <<'PY'
import sys
conf, odir, fid = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(conf, encoding="utf-8").read()
marker = "        # ===== ONLYOFFICE ====="
block = """        # 大字体子集化（deploy/onlyoffice/install_font_subsets.sh）：把 /docs/<版本>/fonts/{fid}
        # 和 sdkjs/common/AllFonts.js 换成宿主机上的瘦身版本
        location ~ "^/docs/[^/]+/fonts/{fid}$" {{
            rewrite ^ /__wf_doc_font_{fid} last;
        }}
        location = /__wf_doc_font_{fid} {{
            default_type font/ttf;
            gzip_static on;
            add_header Cache-Control "public, max-age=31536000, immutable" always;
            alias {odir}/{fid};
        }}
        location ~ "^/docs/[^/]+/sdkjs/common/AllFonts\\.js$" {{
            rewrite ^ /__wf_doc_allfonts last;
        }}
        location = /__wf_doc_allfonts {{
            default_type application/javascript;
            gzip_static on;
            add_header Cache-Control "no-cache" always;
            alias {odir}/AllFonts.js;
        }}

""".format(fid=fid, odir=odir)
assert marker in s, "在 nginx 配置里没找到 ONLYOFFICE 段落标记"
open(conf, "w", encoding="utf-8").write(s.replace(marker, block + marker, 1))
print("  nginx 配置已更新")
PY
fi

echo "[3/3] 校验并 reload nginx"
nginx -t
systemctl reload nginx
echo "完成。验证（应看到 font 请求变小）："
echo "  curl -s -o /dev/null -w '%{size_download}\\n' -H 'Accept-Encoding: gzip' https://<域名>/docs/<版本>/fonts/${FONT_ID}"
echo "  curl -s https://<域名>/docs/<版本>/sdkjs/common/AllFonts.js | grep -c '\"WenQuanYi Zen Hei Mono\",217,0'"
