#!/bin/bash
# 回滚字体子集化：恢复 install_font_subsets.sh 执行前的 nginx 配置并 reload。
# （字体文件、AllFonts.js 的覆盖文件留在 /root/onlyoffice/fonts-override，不再被引用）
set -e
CONF=/etc/nginx/conf.d/pan.conf
LATEST=$(ls -t "$CONF".bak-font-* 2>/dev/null | head -1)
if [ -z "$LATEST" ]; then
    echo "没找到 $CONF.bak-font-* 备份，手动删掉 /__wf_doc_font_* 段落再 reload"
    exit 1
fi
cp -f "$LATEST" "$CONF"
nginx -t
systemctl reload nginx
echo "已回滚到 $LATEST（ONLYOFFICE 原字体原样提供）"
