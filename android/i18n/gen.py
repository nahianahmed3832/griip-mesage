"""Writes res/values{,-zh-rCN,-bn}/strings.xml from strings.py (docs/LANGUAGES.md). Run: python3 -I android/i18n/gen.py"""
import sys, re, os
sys.path.insert(0, os.path.dirname(__file__))
from strings import S
res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
def esc(v):
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = re.sub(r'(?<!\\)"', r'\\"', v)
    v = re.sub(r"(?<!\\)'", r"\\'", v)
    if v[:1] in ("@", "?"):  # a leading @ or ? would make Android read the value as a resource reference
        v = "\\" + v
    return v
brand = {
  "en": ("Private by design. Connected by everyone.",),
  "zh": ("设计即隐私，连接每一个人。",),
  "bn": ("নকশাতেই গোপনীয়। সবার সঙ্গে সংযুক্ত।",),
}
for lang, folder, idx in [("en", "values", 0), ("zh", "values-zh-rCN", 1), ("bn", "values-bn", 2)]:
    os.makedirs(f"{res}/{folder}", exist_ok=True)
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Interface strings (docs/LANGUAGES.md). Generated from one table so every language has every key. -->', '<resources>']
    if lang == "en":
        out += ['    <string name="app_name" translatable="false">Orbit</string>', '    <string name="brand_attribution" translatable="false">Orbit by GriipBuddy</string>']
    out.append(f'    <string name="brand_tagline">{esc(brand[lang][0])}</string>')
    for k, v in S.items():
        out.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    out.append('</resources>')
    open(f"{res}/{folder}/strings.xml", "w").write("\n".join(out) + "\n")
    print(folder, len(S) + (3 if lang == "en" else 1))
