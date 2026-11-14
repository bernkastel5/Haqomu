#!/usr/bin/env python3
"""
Индекс из дампа Kaikki (Wiktionary, арабский раздел) для лукапа.

Дамп: https://kaikki.org/dictionary/Arabic/kaikki.org-dictionary-Arabic.jsonl.gz
(~72 МБ gz, 233k записей, CC BY-SA — при распространении нужна атрибуция).

Что делает:
  * оставляет только арабские слова;
  * разворачивает form_of («мн. ч. от X») в указатель на лемму;
  * строит два уровня ключей: exact (только диакритика убрана) и norm
    (алиф/йа/та-марбута приведены к одному виду) — чтобы `المرأة` (женщина)
    не склеивалось с `مرآة` (зеркало);
  * кладёт словоформы в отдельный индекс form -> lemma для деинфлектора.

Выход: data/packs/kaikki/kaikki_index.jsonl.gz
  {"kind":"entry","key_exact":..,"key_norm":..,"word":..,"pos":..,"gloss":..,"tags":[..],"root":..}
  {"kind":"form","key_exact":..,"key_norm":..,"form":..,"lemma":..}

Запуск:
    python3 tools/build_kaikki_index.py [--src data/raw/kaikki-ar.jsonl.gz] [--out ...]
"""
import argparse
import gzip
import json
import os
import re
import sys
import unicodedata
from collections import Counter

AR = re.compile(r"[^\u0600-\u06FF\s]")
DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u0640]")
# теги, из-за которых смысл почти бесполезен в попапе
BAD_TAGS = {"obsolete", "archaic", "rare", "nonstandard", "misspelling",
            "misspelling-of", "alternative-form-of", "form-of", "no-gloss"}


def strip_diac(s):
    return DIAC.sub("", unicodedata.normalize("NFC", s or "")).strip()


def norm(s):
    s = strip_diac(s)
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = s.replace("ة", "ه")
    return re.sub(r"[^\u0600-\u06FF]", "", s)


def clean_ar(s):
    return bool(s) and not AR.search(s)


def pick_gloss(senses):
    """Первый осмысленный английский глосс + накопленные теги."""
    for s in senses:
        tags = set(s.get("tags") or [])
        g = (s.get("glosses") or [])
        if not g:
            continue
        if tags & {"obsolete", "archaic", "no-gloss"}:
            continue
        txt = g[0].strip()
        if not txt or txt.startswith(("form of", "verbal noun of", "plural of")):
            continue
        return txt, sorted(tags), s
    # если ничего — берём хоть что-то
    for s in senses:
        g = (s.get("glosses") or [])
        if g:
            return g[0].strip(), sorted(set(s.get("tags") or [])), s
    return None, [], None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/raw/kaikki-ar.jsonl.gz")
    ap.add_argument("--out", default="data/packs/kaikki/kaikki_index.jsonl.gz")
    a = ap.parse_args()

    entries = []          # (key_exact, key_norm, word, pos, gloss, tags, root)
    forms = []            # (key_exact, key_norm, form, lemma_norm)
    form_of = {}          # norm(word) -> [lemma words]  для недоразвёрнутых
    seen = set()
    st = Counter()

    opener = gzip.open if a.src.endswith(".gz") else open
    with opener(a.src, "rt", encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            w = d.get("word") or ""
            if not clean_ar(w):
                st["не арабское"] += 1
                continue
            pos = d.get("pos") or ""
            gloss, tags, sense = pick_gloss(d.get("senses") or [])
            root = ""
            for s in (d.get("senses") or []):
                for c in (s.get("categories") or []):
                    m = re.match(r"Arabic terms belonging to the root (.+)", c.get("name") or "")
                    if m:
                        root = m.group(1).replace(" ", "")
                        break
                if root:
                    break
            if gloss:
                ke, kn = strip_diac(w), norm(w)
                sig = (kn, pos, gloss)
                if sig not in seen:
                    seen.add(sig)
                    entries.append((ke, kn, strip_diac(w), pos, gloss[:300], tags, root))
                st["статья с глоссом"] += 1
            # form_of: «множественное от ...» — разворачиваем в указатель
            fo = None
            for s in (d.get("senses") or []):
                for t in (s.get("form_of") or []):
                    if clean_ar(t.get("word") or ""):
                        fo = t["word"]
                        break
                if fo:
                    break
            if fo:
                form_of.setdefault(norm(w), []).append(norm(fo))
                st["form_of"] += 1
            # словоформы из карточки
            for fm in (d.get("forms") or []):
                ff = fm.get("form") or ""
                if not clean_ar(ff) or ff.startswith("ـ"):
                    continue
                ftags = set(fm.get("tags") or [])
                if ftags & {"romanization", "canonical"} and len(ftags) == 1:
                    continue
                forms.append((strip_diac(ff), norm(ff), strip_diac(ff), norm(w)))
                st["словоформа"] += 1

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with gzip.open(a.out, "wt", encoding="utf-8") as f:
        for ke, kn, word, pos, gloss, tags, root in entries:
            f.write(json.dumps({"kind": "entry", "key_exact": ke, "key_norm": kn, "word": word,
                                "pos": pos, "gloss": gloss, "tags": tags, "root": root},
                               ensure_ascii=False) + "\n")
        for kn, lemmas in form_of.items():
            for lm in lemmas:
                f.write(json.dumps({"kind": "form_of", "key_norm": kn, "lemma": lm},
                                   ensure_ascii=False) + "\n")
        for ke, kn, form, lemma in forms:
            f.write(json.dumps({"kind": "form", "key_exact": ke, "key_norm": kn,
                                "form": form, "lemma": lemma}, ensure_ascii=False) + "\n")

    print("статей:", len(entries), "| form_of:", sum(len(v) for v in form_of.values()),
          "| словоформ:", len(forms))
    for k, v in st.most_common():
        print(f"  {k}: {v}")
    print("индекс:", a.out, f"({os.path.getsize(a.out)/1e6:.1f} МБ)")


if __name__ == "__main__":
    main()
