#!/usr/bin/env python3
"""发版前用词检查：扫描用户可见文案中的不当用词与 AI 腔。

用法：python tools/wording_check.py   （在仓库根目录执行，exit 0=通过 / 1=发现问题）

检查范围：
  - app/src/main/res/values*/strings.xml（所有语言的用户可见文案）
  - README.md（仓库对外文档）
  - app/src/main/java/**/*.java 里字符串字面量（Toast/setText 等硬编码）
检查词表：不当代称 + 常见 AI 腔营销词。发现即报文件、行号、原文。
发版流程要求：每次发布 Release 前必须先跑本脚本并清零问题。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

BAD_WORDS = [
    # 不当代称
    "主人", "小主", "亲爱的用户",
    # AI 腔 / 营销腔
    "赋能", "助力", "畅享", "尽在", "打造", "为您打造", "智慧", "美好体验",
    "一触即达", "开启美好", "极致体验", "一站式", "全方位", "贴心",
    "温馨提示", "恭喜您", "欢迎来到", "解锁新", "全新升级",
]

def scan_text(rel: str, text: str, hits: list) -> None:
    for i, line in enumerate(text.splitlines(), 1):
        for w in BAD_WORDS:
            if w in line:
                hits.append(f"{rel}:{i}: [{w}] {line.strip()[:100]}")

def main() -> int:
    hits: list = []
    # 1) strings.xml（所有语言目录）
    for p in sorted((ROOT / "app/src/main/res").glob("values*/strings.xml")):
        scan_text(p.relative_to(ROOT).as_posix(), p.read_text(encoding="utf-8"), hits)
    # 2) README
    readme = ROOT / "README.md"
    if readme.exists():
        scan_text("README.md", readme.read_text(encoding="utf-8"), hits)
    # 3) Java 字符串字面量（粗扫：引号内含中文或词表词）
    for p in sorted((ROOT / "app/src/main/java").rglob("*.java")):
        text = p.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(text.splitlines(), 1):
            for w in BAD_WORDS:
                if w in line and ('"' in line or "//" in line):
                    hits.append(f"{p.relative_to(ROOT).as_posix()}:{i}: [{w}] {line.strip()[:100]}")
    if hits:
        print(f"发现 {len(hits)} 处问题用词：")
        for h in hits:
            print("  ", h)
        return 1
    print("用词检查通过：无不当用词。")
    return 0

if __name__ == "__main__":
    sys.exit(main())
