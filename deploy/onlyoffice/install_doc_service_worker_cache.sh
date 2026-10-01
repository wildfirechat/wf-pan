#!/bin/bash
# 让在线文档编辑器"打开一次就缓存住"，不再每次打开都重下十几 MB。
#
# 做两件事：
#   1. 从 wf-docs 容器里取出 ONLYOFFICE 的 document_editor_service_worker.js，
#      打上补丁（大文件也缓存，见 patch_doc_service_worker.py），放到宿主机 /root/onlyoffice/sw/；
#   2. 在 nginx（/etc/nginx/conf.d/pan.conf）里把该 URL 指到这份补丁版，然后 reload nginx。
#
# 为什么用 nginx 覆盖而不是改容器：容器一重建补丁就没了，放宿主机由 nginx 直接返回更稳。
# 官方文件在 /root/onlyoffice/sw-backup/ 有备份，重复执行是幂等的。
set -e

CONTAINER=${CONTAINER:-wf-docs}
DOC_DIR=/var/www/onlyoffice/documentserver
SW_DIR=/root/onlyoffice/sw
SW_BAK=/root/onlyoffice/sw-backup
SW_URL_PATH='/docs/[^/]+/document_editor_service_worker\.js'
CONF=/etc/nginx/conf.d/pan.conf
HERE=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$SW_DIR" "$SW_BAK"

echo "[1/3] 从 $CONTAINER 取官方 Service Worker 并打补丁"
docker cp "$CONTAINER:$DOC_DIR/sdkjs/common/serviceworker/document_editor_service_worker.js" "$SW_BAK/document_editor_service_worker.orig.js"
python3 "$HERE/patch_doc_service_worker.py" "$SW_BAK/document_editor_service_worker.orig.js" "$SW_DIR/document_editor_service_worker.js"

echo "[2/3] 写 nginx 覆盖规则"
if grep -q "__pan_doc_service_worker" "$CONF"; then
    echo "  已存在，跳过"
else
    cp -f "$CONF" "$CONF.bak-sw-$(date +%Y%m%d%H%M%S)"
    python3 - "$CONF" "$SW_DIR/document_editor_service_worker.js" <<'PY'
import sys
conf, sw = sys.argv[1], sys.argv[2]
s = open(conf, encoding="utf-8").read()
marker = "        # ===== ONLYOFFICE ====="
block = """        # 在线文档 Service Worker：换成改过的版本（大文件也缓存，见 deploy/onlyoffice/）
        location ~ \"^/docs/[^/]+/document_editor_service_worker\\\\.js$\" {
            default_type application/javascript;
            add_header Cache-Control \"no-cache\" always;
            rewrite ^ /__pan_doc_service_worker last;
        }
        location = /__pan_doc_service_worker {
            default_type application/javascript;
            add_header Cache-Control \"no-cache\" always;
            alias %s;
        }

""" % sw
assert marker in s, "在 nginx 配置里没找到 ONLYOFFICE 段落标记"
open(conf, "w", encoding="utf-8").write(s.replace(marker, block + marker, 1))
print("  nginx 配置已更新")
PY
fi

echo "[3/3] 校验并 reload nginx"
nginx -t
systemctl reload nginx
echo "完成。验证："
echo "  curl -s https://<你的域名>/docs/<版本>/document_editor_service_worker.js | grep -c wf-patch   # 应该是 1"
