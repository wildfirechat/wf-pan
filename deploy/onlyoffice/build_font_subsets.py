#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ONLYOFFICE 的大字体（中文/日文/韩文）子集化，减小客户端打开文档时要下载的资源。

背景：`fonts/217`（文泉驿正黑）gzip 后有 9.18MB，客户端只要有中文就会出现这个请求；
`fonts/134/135`（NanumGothic）、`fonts/179/182`（Noto Sans KR）等 2~4MB。子集化之后只保留
常用字符（拉丁 + 标点 + CJK 统一表意文字等），字体文件能小 50%~80%。

字体文件的前 32 字节是 ONLYOFFICE 的混淆（与固定 16 字节密钥异或），处理流程：
  还原 → fonttools 子集 → 重新混淆 → gzip -9

依赖 fonttools：python3 -m venv /root/fontenv && /root/fontenv/bin/pip install fonttools

用法：
  # 1) 先把要处理的字体从容器里拷出来
  docker cp wf-docs:/var/www/onlyoffice/documentserver/fonts/217 /root/fonts-src/217
  docker cp wf-docs:/var/www/onlyoffice/documentserver/fonts/217.gz /root/fonts-src/217.gz
  # 2) 生成子集
  python3 build_font_subsets.py --src /root/fonts-src --out /root/fonts-subset --fonts 217,134,135,179,182
  # 3) 看报告，确认没有明显缺字（--ext-a 可包含 CJK 扩展 A）
"""
import argparse
import gzip
import io
import os
import shutil
import sys

from fontTools.ttLib import TTFont, TTCollection
from fontTools import subset

# ONLYOFFICE 字体文件的混淆密钥（前 32 字节与这 16 字节循环异或）
KEY = bytes.fromhex('a066d620149647fa9569b850b0414948')


def deobfuscate(data):
    return bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data[:32])) + data[32:]


def obfuscate(data):
    return bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data[:32])) + data[32:]


def target_codepoints(ext_a=False):
    """子集保留的码点：拉丁 + 常用符号 + CJK 标点/假名/全角 + CJK 统一表意文字"""
    cps = set()

    def add(lo, hi):
        cps.update(range(lo, hi + 1))

    add(0x20, 0x7E)        # ASCII
    add(0xA0, 0xFF)        # Latin-1
    add(0x100, 0x17F)      # Latin Extended-A
    add(0x2000, 0x206F)    # General Punctuation
    add(0x20A0, 0x20BF)    # Currency Symbols
    add(0x2100, 0x214F)    # Letterlike Symbols
    add(0x2150, 0x218F)    # Number Forms
    add(0x2190, 0x21FF)    # Arrows
    add(0x2200, 0x22FF)    # Mathematical Operators
    add(0x2460, 0x24FF)    # Enclosed Alphanumerics
    add(0x25A0, 0x25FF)    # Geometric Shapes
    add(0x2600, 0x26FF)    # Miscellaneous Symbols
    add(0x3000, 0x303F)    # CJK Symbols and Punctuation
    add(0x3040, 0x30FF)    # Hiragana + Katakana
    add(0x3100, 0x312F)    # Bopomofo
    add(0x3200, 0x33FF)    # Enclosed CJK / CJK Compatibility
    add(0x4E00, 0x9FFF)    # CJK Unified Ideographs
    add(0x1100, 0x11FF)    # Hangul Jamo
    add(0x3130, 0x318F)    # Hangul Compatibility Jamo
    add(0xAC00, 0xD7A3)    # Hangul Syllables
    add(0xA960, 0xA97F)    # Hangul Jamo Extended-A
    add(0xD7B0, 0xD7FF)    # Hangul Jamo Extended-B
    add(0xF900, 0xFAFF)    # CJK Compatibility Ideographs
    add(0xFE30, 0xFE4F)    # CJK Compatibility Forms
    add(0xFF00, 0xFFEF)    # Halfwidth and Fullwidth Forms
    if ext_a:
        add(0x3400, 0x4DBF)  # CJK Extension A
    return cps


def make_options():
    opts = subset.Options()
    opts.layout_features = ['*']      # 保留 OpenType 特性（连字、替代字形等）
    opts.name_IDs = ['*']             # 保留字体名（客户端按名字选字体）
    opts.name_legacy = True
    opts.name_languages = ['*']
    opts.notdef_outline = True
    opts.recommended_glyphs = True
    opts.symbol_cmap = True
    opts.legacy_cmap = True
    opts.glyph_names = False           # 不要为每个字形生成 post 名字，会显著变胖
    opts.recalc_bounds = False
    opts.recalc_timestamp = False
    opts.hinting = True               # 保留 hinting，避免小字号排版变化
    opts.desubroutinize = False
    opts.drop_tables = []
    return opts


def subset_one(font, cps, opts):
    s = subset.Subsetter(options=opts)
    s.populate(unicodes=cps)
    s.subset(font)


def cmap_codepoints(font):
    cps = set()
    for table in font['cmap'].tables:
        try:
            cps.update(table.cmap.keys())
        except Exception:
            pass
    return cps


def process(src, out, font_id, cps, ext_a, face_only=None):
    raw = open(os.path.join(src, font_id), 'rb').read()
    data = deobfuscate(raw)
    opts = make_options()
    if data[:4] == b'ttcf':
        coll = TTCollection(io.BytesIO(data), lazy=False)
        if face_only is not None:
            # 只保留指定 face，输出单 face 字体：TTC 子集化后各 face 的字形表会各存一份，
            # 反而比原文件更大；文档实际用的是 face 0（WenQuanYi Zen Hei）。
            face = coll.fonts[face_only]
            before = [len(cmap_codepoints(face))]
            subset_one(face, cps, opts)
            after = [len(cmap_codepoints(face))]
            buf = io.BytesIO()
            face.save(buf)
            result = buf.getvalue()
            kind = 'TTC face%d -> TTF' % face_only
        else:
            before = [len(cmap_codepoints(f)) for f in coll.fonts]
            for f in coll.fonts:
                subset_one(f, cps, opts)
            after = [len(cmap_codepoints(f)) for f in coll.fonts]
            buf = io.BytesIO()
            coll.save(buf, shareTables=True)   # TTC 的多个 face 共享字形表，不共享会翻倍
            result = buf.getvalue()
            kind = 'TTC(%d faces)' % len(coll.fonts)
    else:
        font = TTFont(io.BytesIO(data), lazy=False)
        before = [len(cmap_codepoints(font))]
        subset_one(font, cps, opts)
        after = [len(cmap_codepoints(font))]
        buf = io.BytesIO()
        font.save(buf)
        result = buf.getvalue()
        kind = 'TTF'

    obf = obfuscate(result)
    open(os.path.join(out, font_id), 'wb').write(obf)
    with gzip.open(os.path.join(out, font_id + '.gz'), 'wb', compresslevel=9) as gz:
        gz.write(obf)

    raw_gz = os.path.getsize(os.path.join(src, font_id + '.gz')) if os.path.exists(os.path.join(src, font_id + '.gz')) else 0
    new_gz = os.path.getsize(os.path.join(out, font_id + '.gz'))
    print('%-5s %-14s %9d -> %9d (raw)   %8d -> %8d (gz, -%d%%)  cps %s -> %s%s' % (
        font_id, kind, len(raw), len(obf), raw_gz, new_gz,
        (100 - new_gz * 100 // raw_gz) if raw_gz else 0,
        before, after, '  [+ExtA]' if ext_a else ''))
    return {'id': font_id, 'raw': (len(raw), len(obf)), 'gz': (raw_gz, new_gz)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True, help='原始 fonts 目录')
    ap.add_argument('--out', required=True, help='输出目录')
    ap.add_argument('--fonts', default='217', help='要处理的字体 id，逗号分隔')
    ap.add_argument('--ext-a', action='store_true', help='额外保留 CJK 扩展 A（更大，但少部分生僻字也能显示）')
    ap.add_argument('--face-only', type=int, default=0,
                    help='TTC 只保留第几个 face（默认 0，输出单 face 字体；TTC 整体子集化会因字形表各存一份而变大）')
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    cps = target_codepoints(args.ext_a)
    print('目标字符集：%d 个码点（extA=%s）' % (len(cps), args.ext_a))
    for fid in [x.strip() for x in args.fonts.split(',') if x.strip()]:
        if not os.path.exists(os.path.join(args.src, fid)):
            print('%-5s 跳过：%s 不存在' % (fid, os.path.join(args.src, fid)))
            continue
        process(args.src, args.out, fid, cps, args.ext_a, args.face_only)
    print('输出目录：%s' % args.out)


if __name__ == '__main__':
    sys.exit(main())
