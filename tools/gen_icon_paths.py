#!/usr/bin/env python3
"""从 RN 侧同一份图标字体导出 24x24 视口的填充路径,生成 IconPaths.generated.kt。

从 tools/icon-names.txt 读取图标名，将 Material Icons Outlined 字形轮廓
导出为路径常量，由 ui/icons/AppIcons.kt 绘制。

跑法(需要 fontTools):
    uv run --with fonttools python tools/gen_icon_paths.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "assets/fonts/MaterialIconsOutlined-Regular.otf"
ICON_NAMES = ROOT / "tools/icon-names.txt"
OUT = ROOT / "app/src/main/kotlin/com/chasel/ng2n/ui/icons/IconPaths.generated.kt"

VIEWPORT = 24.0


def icon_names() -> list[str]:
    return ICON_NAMES.read_text(encoding="utf-8").splitlines()


def fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def main() -> int:
    font = TTFont(FONT)
    upem = font["head"].unitsPerEm
    scale = VIEWPORT / upem
    cmap = font.getBestCmap()
    by_name = {g: cp for cp, g in cmap.items()}
    glyphs = font.getGlyphSet()

    names = icon_names()
    assert names, "没从 tools/icon-names.txt 里扫到图标名"
    names = sorted(set(names))

    rows: list[tuple[str, int, str]] = []
    for name in names:
        if name not in glyphs:
            print(f"字体里没有字形:{name}", file=sys.stderr)
            return 1
        pen = SVGPathPen(glyphs, ntos=fmt)
        # 字体坐标 y 向上、基线在 0;图标框正好是 em 框 [0, upem],翻成 y 向下的 24 视口
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, 0, VIEWPORT)))
        d = pen.getCommands()
        if not d:
            print(f"字形是空的:{name}", file=sys.stderr)
            return 1
        rows.append((name, by_name.get(name, 0), d))

    lines = [
        "// 本文件由 tools/gen_icon_paths.py 生成,请勿手改。",
        "// 轮廓取自 assets/fonts/MaterialIconsOutlined-Regular.otf(Apache-2.0),",
        "// 图标名由 tools/icon-names.txt 维护。",
        "",
        "package com.chasel.ng2n.ui.icons",
        "",
        "/** 路径数据的坐标视口边长(与 Material 图标的 24 格设计栅格一致)。 */",
        "const val ICON_VIEWPORT: Float = 24f",
        "",
        "/**",
        " * Material 图标名 → 24 视口下的填充路径(SVG path 语法,非零环绕)。",
        " *",
        " * 图标名由 tools/icon-names.txt 维护。",
        " */",
        "val ICON_PATHS: Map<String, String> = mapOf(",
    ]
    for name, cp, d in rows:
        lines.append(f"  // U+{cp:04X}")
        lines.append(f'  "{name}" to')
        lines.append(f'    "{d}",')
    lines.append(")")
    lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"{len(rows)} 颗字形 → {OUT.relative_to(ROOT)}  ({OUT.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
