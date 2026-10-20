#!/usr/bin/env python3
"""
把 i18n/<语言>.txt 生成为 app/src/main/res/values-*/strings.xml。

- en.txt 是主文件（也是 values/ 默认语言），其他语言缺的条目自动用英文；
- 检查每条翻译的占位符（%1$s、%2$d …）是否与英文一致，不一致直接报错；
- 自动转义 Android 字符串里的 ' " @ ? 等特殊字符。

用法：python3 i18n/build_strings.py      （改完任何 .txt 后运行，再编译）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RES = ROOT.parent / "app/src/main/res"

# 文件名 → Android 资源目录（可多个）
LOCALES = {
    "en": ["values"],
    "zh": ["values-zh"],                      # 简体（中国大陆、新加坡）
    "zh-Hant": ["values-zh-rTW", "values-zh-rHK", "values-zh-rMO"],
    "ja": ["values-ja"],
    "ko": ["values-ko"],
    "fr": ["values-fr"],
    "de": ["values-de"],
    "es": ["values-es"],
    "it": ["values-it"],
    "pt": ["values-pt"],
    "ru": ["values-ru"],
    "pl": ["values-pl"],
    "nl": ["values-nl"],
    "tr": ["values-tr"],
    "vi": ["values-vi"],
    "th": ["values-th"],
    "id": ["values-in"],                      # Android 用旧代码 in 表示印尼语
}

PH = re.compile(r"%(\d+\$)?[sd]|%%")


def parse(path: Path):
    strings, arrays = {}, {}
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if " = " not in line:
            sys.exit(f"{path.name}:{n}: 缺少 ' = '")
        key, val = line.split(" = ", 1)
        key = key.strip()
        if key.endswith("[]"):
            arrays[key[:-2]] = [v.strip() for v in val.split(" | ")]
        else:
            strings[key] = val.rstrip()  # 保留开头空格（如「 · 混音」后缀）
    return strings, arrays


def esc(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s  # \n 和   原样保留，Android 会解释


def placeholders(s: str):
    return sorted(m.group(0) for m in PH.finditer(s))


def main():
    en_s, en_a = parse(ROOT / "en.txt")
    errors = []
    for lang, dirs in LOCALES.items():
        f = ROOT / f"{lang}.txt"
        if not f.exists():
            errors.append(f"缺少 {f.name}")
            continue
        tr_s, tr_a = parse(f)
        for k in tr_s:
            if k not in en_s:
                errors.append(f"{lang}: 多余的键 {k}")
            elif placeholders(tr_s[k]) != placeholders(en_s[k]):
                errors.append(f"{lang}: {k} 占位符不一致 {placeholders(tr_s[k])} ≠ {placeholders(en_s[k])}")
        for k in tr_a:
            if k in en_a and len(tr_a[k]) != len(en_a[k]):
                errors.append(f"{lang}: 数组 {k} 长度 {len(tr_a[k])} ≠ {len(en_a[k])}")
        missing = [k for k in en_s if k not in tr_s] + [k for k in en_a if k not in tr_a]
        if missing and lang != "en":
            print(f"  {lang}: {len(missing)} 条未翻译，用英文：{', '.join(missing[:6])}{'…' if len(missing) > 6 else ''}")
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- 由 i18n/build_strings.py 生成，请修改 i18n/" + f.name + " -->", "<resources>"]
        for k, v in en_s.items():
            text = tr_s.get(k, v)
            extra = ' formatted="false"' if "%" in text and not PH.search(text.replace("%%", "")) and "%%" not in text else ""
            body = esc(text)
            # 开头的空格会被 Android 资源编译吞掉，整体加引号才能保留
            if text.startswith(" "):
                body = '"' + body + '"'
            out.append(f'    <string name="{k}"{extra}>{body}</string>')
        for k, items in en_a.items():
            out.append(f'    <string-array name="{k}">')
            for it in tr_a.get(k, items):
                out.append(f"        <item>{esc(it)}</item>")
            out.append("    </string-array>")
        out.append("</resources>\n")
        for d in dirs:
            p = RES / d / "strings.xml"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("\n".join(out), encoding="utf-8")
    if errors:
        print("\n".join("✗ " + e for e in errors))
        sys.exit(1)
    print(f"✓ 生成 {len(LOCALES)} 种语言，{len(en_s)} 条字符串 + {len(en_a)} 组菜单")


if __name__ == "__main__":
    main()
