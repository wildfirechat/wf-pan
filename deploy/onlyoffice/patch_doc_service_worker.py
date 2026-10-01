#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 ONLYOFFICE 自带的 document_editor_service_worker.js 打补丁。

为什么要改：
 官方 Service Worker 对静态资源有一个单文件大小上限
 （maxEntrySize = min(storage 配额 * 10%, 1GiB) / 8），还要过 isHealthy（磁盘占用 <80%）判断。
 在线文档打开一次要下十几 MB（sdkjs/word/sdk-all.js、fonts/217 中文字体等），
 移动端 WebView 的配额估计值往往很小/磁盘偏满，于是这些大文件不写进 Cache Storage，
 每次打开文档都重新下载一遍。

补丁内容（只改 cacheFirst 里取 storage 信息那一处）：
 带版本号的静态资源（web-apps/ sdkjs/ fonts/ sdkjs-plugins/ dictionaries/）不受
 配额估算和健康度限制，一律缓存（单文件上限给到 512MB）；带文档 id 的动态文件仍走原来的
 FIFO 逻辑，不受影响。

用法（在部署了 wf-docs 容器的机器上执行）：
  python3 patch_doc_service_worker.py <原始文件> <输出文件>
原始文件一般用：
  docker cp wf-docs:/var/www/onlyoffice/documentserver/sdkjs/common/serviceworker/document_editor_service_worker.js ./sw.js
"""
import sys

OLD = """				if (safeToCache(request, networkResp)) {
					event.waitUntil(
						getStorageInfo()
						.then(function(info) {"""

NEW = """				if (safeToCache(request, networkResp)) {
					// [wf-patch] 编辑器静态资源（web-apps/ sdkjs/ fonts/ sdkjs-plugins/ dictionaries/）都带版本号、
					// 内容不会变，而且是"打开一次要下十几 MB"的大头；不受配额/磁盘健康度/单文件上限限制，一律缓存。
					const storageInfoPromise = matchesCacheablePath(url)
						? Promise.resolve({ isHealthy: true, maxEntrySize: 512 * 1024 * 1024 })
						: getStorageInfo();
					event.waitUntil(
						storageInfoPromise
						.then(function(info) {"""

MARK = "[wf-patch]"


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src_path, dst_path = sys.argv[1], sys.argv[2]
    with open(src_path, encoding="utf-8") as f:
        src = f.read()
    if MARK in src:
        print("already patched, nothing to do")
    else:
        if src.count(OLD) != 1:
            print("ERROR: 没找到预期代码（ONLYOFFICE 版本变了？），count=%d" % src.count(OLD))
            return 1
        src = src.replace(OLD, NEW, 1)
        print("patched")
    with open(dst_path, "w", encoding="utf-8") as f:
        f.write(src)
    return 0


if __name__ == "__main__":
    sys.exit(main())
