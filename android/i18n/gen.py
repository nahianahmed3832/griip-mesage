"""Writes res/values{,-zh-rCN,-bn}/strings.xml from strings.py, strings_account.xml from strings_account.py and
strings_workspace.xml from strings_workspace.py (docs/LANGUAGES.md). Run: python3 -I -B android/i18n/gen.py (-B: don't rewrite the tracked __pycache__)."""
import sys, re, os
sys.path.insert(0, os.path.dirname(__file__))
from strings import S
from strings_account import S as ACCOUNT
from strings_workspace import S as WORKSPACE
from strings_apps import S as APPS
from strings_meldiq import S as MELDIQ
from strings_round6 import S as ROUND6
from strings_round7 import S as ROUND7
res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
# Every key must be unique across the tables (Android merges them into one namespace).
_seen = {}
for _name, _table in [("strings", S), ("strings_account", ACCOUNT), ("strings_workspace", WORKSPACE), ("strings_apps", APPS), ("strings_meldiq", MELDIQ), ("strings_round6", ROUND6), ("strings_round7", ROUND7)]:
    for _k in _table:
        if _k in _seen:
            raise SystemExit(f"duplicate string key {_k!r} in {_name} and {_seen[_k]}")
        _seen[_k] = _name
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
        out += ['    <string name="app_name" translatable="false">Meldiq</string>', '    <string name="brand_attribution" translatable="false">Meldiq by GriipBuddy</string>']
    out.append(f'    <string name="brand_tagline">{esc(brand[lang][0])}</string>')
    for k, v in S.items():
        out.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    out.append('</resources>')
    open(f"{res}/{folder}/strings.xml", "w").write("\n".join(out) + "\n")
    print(folder, len(S) + (3 if lang == "en" else 1))
    # Orbit 2.0 account strings: their own table and file (strings_account.py).
    acct = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Orbit 2.0 account strings (docs/LANGUAGES.md). Generated from strings_account.py. -->', '<resources>']
    for k, v in ACCOUNT.items():
        acct.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    acct.append('</resources>')
    open(f"{res}/{folder}/strings_account.xml", "w").write("\n".join(acct) + "\n")
    print(folder, "account", len(ACCOUNT))
    # Workspace administration strings (2026-10-19): strings_workspace.py.
    ws = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Workspace administration strings (docs/LANGUAGES.md). Generated from strings_workspace.py. -->', '<resources>']
    for k, v in WORKSPACE.items():
        ws.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    ws.append('</resources>')
    open(f"{res}/{folder}/strings_workspace.xml", "w").write("\n".join(ws) + "\n")
    print(folder, "workspace", len(WORKSPACE))
    # Custom roles, apps and administration strings (2026-10-19): strings_apps.py.
    ap = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Roles, apps and administration strings (docs/LANGUAGES.md). Generated from strings_apps.py. -->', '<resources>']
    for k, v in APPS.items():
        ap.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    ap.append('</resources>')
    open(f"{res}/{folder}/strings_apps.xml", "w").write("\n".join(ap) + "\n")
    print(folder, "apps", len(APPS))
    # Meldiq design, messaging and media strings (2026-10-19 round 5): strings_meldiq.py.
    md = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Meldiq design, messaging and media strings (docs/LANGUAGES.md). Generated from strings_meldiq.py. -->', '<resources>']
    for k, v in MELDIQ.items():
        md.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    md.append('</resources>')
    open(f"{res}/{folder}/strings_meldiq.xml", "w").write("\n".join(md) + "\n")
    print(folder, "meldiq", len(MELDIQ))
    # Round 6 strings (2026-10-19): strings_round6.py.
    r6 = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Round 6 strings (docs/LANGUAGES.md). Generated from strings_round6.py. -->', '<resources>']
    for k, v in ROUND6.items():
        r6.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    r6.append('</resources>')
    open(f"{res}/{folder}/strings_round6.xml", "w").write("\n".join(r6) + "\n")
    print(folder, "round6", len(ROUND6))
    # Round 7 strings (2026-10-19): strings_round7.py.
    r7 = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Round 7 strings (docs/LANGUAGES.md). Generated from strings_round7.py. -->', '<resources>']
    for k, v in ROUND7.items():
        r7.append(f'    <string name="{k}">{esc(v[idx])}</string>')
    r7.append('</resources>')
    open(f"{res}/{folder}/strings_round7.xml", "w").write("\n".join(r7) + "\n")
    print(folder, "round7", len(ROUND7))
