"""Builds Meldiq's emoji catalogue (docs/EMOJI.md) from Unicode's own data, for Android and Meldiq Web.

Inputs (download them into an empty folder; they're data, never run):
  emoji-test.txt                https://unicode.org/Public/emoji/15.1/emoji-test.txt
  annotations-en.json           https://raw.githubusercontent.com/unicode-org/cldr-json/46.0.0/cldr-json/cldr-annotations-full/annotations/en/annotations.json
  annotationsDerived-en.json    https://raw.githubusercontent.com/unicode-org/cldr-json/46.0.0/cldr-json/cldr-annotations-derived-full/annotationsDerived/en/annotations.json

Run: python3 -I tools/emoji/gen_emoji.py <inputs folder> <output.json> [<more outputs>...]

Output: {"v": "15.1", "groups": [{"id", "name", "items": [[emoji, name, keywords, version, tones?]]}]}
- only fully-qualified emoji, in Unicode's recommended (CLDR) order, without the Component group;
- skin-tone variants folded into their base: `tones` lists the five one-tone variants, light to dark, when Unicode
  has all five (two-person emoji with mixed tones are left out: the picker offers one tone at a time);
- `keywords`: CLDR's English search words, space-separated, lowercase; `version`: the Emoji version that added it,
  so an app can hide what its fonts can't draw.
The data is © Unicode, Inc., under the Unicode License v3 (docs/licenses/UNICODE-LICENSE.txt).
"""
import json
import re
import sys

GROUP_IDS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "people",
    "Animals & Nature": "nature",
    "Food & Drink": "food",
    "Travel & Places": "travel",
    "Activities": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}
TONES = ["light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone", "dark skin tone"]
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*\S+\s+E(\d+\.\d+)\s+(.+)$")


def annotations(path, key):
    return json.load(open(path, encoding="utf-8"))[key]["annotations"]


def main(src, outs):
    plain = annotations(f"{src}/annotations-en.json", "annotations")
    derived = annotations(f"{src}/annotationsDerived-en.json", "annotationsDerived")

    def words(e):
        a = plain.get(e) or derived.get(e) or plain.get(e.replace("️", "")) or derived.get(e.replace("️", "")) or {}
        return a.get("default", []), (a.get("tts") or [None])[0]

    groups, by_name = [], {}
    group = None
    for raw in open(f"{src}/emoji-test.txt", encoding="utf-8"):
        line = raw.rstrip("\n")
        if line.startswith("# group:"):
            name = line.split(":", 1)[1].strip()
            group = {"id": GROUP_IDS[name], "name": name, "items": []} if name in GROUP_IDS else None
            if group:
                groups.append(group)
            continue
        m = LINE.match(line)
        if not m or group is None:
            continue
        emoji = "".join(chr(int(cp, 16)) for cp in m.group(1).split())
        version, name = m.group(2), m.group(3)
        if ": " in name and name.split(": ", 1)[1] in TONES:
            base_name, tone = name.split(": ", 1)
            base = by_name.get(base_name)
            if base is not None:
                base.setdefault("tones", {})[tone] = emoji
            continue
        if ": " in name and any(t in name.split(": ", 1)[1] for t in TONES):
            continue  # mixed tones, or a tone plus something else
        keywords, tts = words(emoji)
        item = {"e": emoji, "n": (tts or name).lower(), "k": keywords, "v": version}
        by_name[name] = item
        group["items"].append(item)

    out = {"v": "15.1", "groups": []}
    for g in groups:
        items = []
        for it in g["items"]:
            # Search words not already in the name, so the file stays small.
            name_words = set(re.split(r"[\s:,]+", it["n"]))
            kw = " ".join(dict.fromkeys(w.lower() for w in it["k"] if w.lower() not in name_words and w.lower() != it["n"]))
            row = [it["e"], it["n"], kw, it["v"]]
            tones = it.get("tones", {})
            if len(tones) == len(TONES):
                row.append([tones[t] for t in TONES])
            items.append(row)
        out["groups"].append({"id": g["id"], "name": g["name"], "items": items})
    text = json.dumps(out, ensure_ascii=False, separators=(",", ":"))
    for path in outs:
        open(path, "w", encoding="utf-8").write(text + "\n")
    total = sum(len(g["items"]) for g in out["groups"])
    toned = sum(1 for g in out["groups"] for r in g["items"] if len(r) > 4)
    print(f"{total} emoji in {len(out['groups'])} groups, {toned} with skin tones, {len(text.encode())} bytes")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2:])
