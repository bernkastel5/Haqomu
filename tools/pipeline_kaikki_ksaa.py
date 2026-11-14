#!/usr/bin/env python3
"""
Пайплайн «Kaikki ищет лемму → KSAA даёт значение».

Зачем: у KSAA главный дефект — он срезает клитики на своей стороне
(`الحظ` → `لحظ`, `السنة` → `لسن`) и не находит словоформы (`يحتاج`, `مشكلتك`).
У Kaikki наоборот: 1,62 млн словоформ и надёжное сведение к лемме, но
английские глоссы и плохой выбор смысла. Комбинация должна брать лучшее
из обоих: лемма от Kaikki, толкование от KSAA.

Шаги на слово:
  1. Kaikki (tools/score_kaikki.py) -> лучшая лемма (head);
  2. запрос KSAA по лемме (кэш в data/quality/ksaa_lemma_cache);
  3. фильтр однословных статей + ранкер KSAA -> top-1.

Выход: data/quality/pipeline200.json + печать таблицы для разметки.

Запуск:
    python3 tools/pipeline_kaikki_ksaa.py [--print] [--top3]
"""
import argparse
import json
import os
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QDIR = os.path.join(HERE, "data", "quality")
CACHE = os.path.join(QDIR, "ksaa_lemma_cache")
LEMMA_IN = os.path.join(QDIR, "kaikki200.json")
OUT = os.path.join(QDIR, "pipeline200.json")
API = "https://siwar.ksaa.gov.sa/api/riyadh/Result/SearchEntriesWeb?word="

DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u0640]")


def nk(s):
    s = DIAC.sub("", unicodedata.normalize("NFC", s or ""))
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = s.replace("ة", "ه")
    return re.sub(r"[^\u0600-\u06FF]", "", s)


def fetch(word):
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, nk(word) + ".json")
    if os.path.exists(p):
        return json.load(open(p, encoding="utf-8"))
    url = API + urllib.parse.quote(word)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        d = json.loads(urllib.request.urlopen(req, timeout=25).read())
        json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False)
        time.sleep(0.12)
        return d
    except Exception as e:
        return {"__error__": str(e)}


def summ(e):
    se = e.get("resultSenseDTO") or []
    f = se[0] if se else {}
    d = f.get("definition") or ""
    return {"entry": e.get("entry"), "unDiac": e.get("unDiacWord"),
            "pos": e.get("mainPOS"), "def": d[:300],
            "en": (f.get("englishTranslation") or "").strip(),
            "single": " " not in (e.get("unDiacWord") or "").strip(),
            "quranic": "قرآني" in d,
            "passive": d.startswith("فعل ماض مبني للمجهول"),
            "root": (f.get("root") or "").strip()}


def rank(entries, lemma):
    """Тот же ранкер, что для KSAA в score_golden: single-word, не коранический, не пассив."""
    q = nk(lemma)
    best = None
    for i, s in enumerate(entries):
        u = nk(s["unDiac"])
        sc = 0.0
        if u == q:
            sc += 100
        elif u.startswith(q) or q.startswith(u):
            sc += 15
        if not s["single"]:
            sc -= 60
        if s["quranic"]:
            sc -= 20
        if s["passive"]:
            sc -= 10
        if s["en"]:
            sc += 3
        sc -= i * 0.5
        if best is None or sc > best[0]:
            best = (sc, s)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", dest="do_print", action="store_true")
    ap.add_argument("--top3", action="store_true", help="пробовать до 3 лемм Kaikki")
    a = ap.parse_args()

    lemmas = json.load(open(LEMMA_IN, encoding="utf-8"))
    plan = []
    for r in lemmas:
        cands = []
        if r.get("head"):
            cands.append(r["head"])
        for alt in (r.get("alts") or []):
            if alt.get("head") and alt["head"] not in cands:
                cands.append(alt["head"])
        plan.append((r["idx"], r["word"], cands[: 3 if a.top3 else 1]))

    need = {c for _, _, cs in plan for c in cs}
    todo = [c for c in need if not os.path.exists(os.path.join(CACHE, nk(c) + ".json"))]
    print(f"лемм к запросу: {len(need)}, из них новых: {len(todo)}")
    with ThreadPoolExecutor(max_workers=6) as p:
        list(p.map(fetch, todo))

    out = []
    for idx, word, cands in plan:
        best = None
        for c in cands:
            raw = fetch(c)
            if not raw or isinstance(raw, dict):
                continue
            entries = [summ(e) for e in raw][:12]
            r = rank(entries, c)
            if r and (best is None or r[0] > best[0]):
                best = (r[0], r[1], c)
        rec = {"idx": idx, "word": word, "lemma": best[2] if best else None,
               "unDiac": best[1]["unDiac"] if best else None,
               "def": best[1]["def"] if best else None,
               "pos": best[1]["pos"] if best else None,
               "score": round(best[0], 1) if best else None}
        out.append(rec)

    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    got = sum(1 for r in out if r["def"])
    print(f"получено толкование: {got}/200")
    c = Counter((r["lemma"] is None) for r in out)
    print("без леммы от Kaikki:", c[True])
    if a.do_print:
        for r in out:
            print(f"{r['idx']:3d} {r['word']:12s} → лемма {str(r['lemma'] or '—'):12s} "
                  f"| KSAA {str(r['unDiac'] or '—'):12s} {(r['def'] or '')[:66]}")
    print("файл:", OUT)


if __name__ == "__main__":
    main()
