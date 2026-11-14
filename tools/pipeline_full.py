#!/usr/bin/env python3
"""
Полный пайплайн попапа: анализатор -> фильтр -> кросс-подтверждение.

Стадии (порядок и правила заданы до прогона):

  1. АНАЛИЗАТОР (farahidi = порт AlKhalil Morpho Sys 2, GPL-3, офлайн).
     Разбирает словоформу в набор чтений (лемма + корень + модель + часть речи).
     Берём до двух разных лемм: сначала то чтение, у которого основа совпадает
     с исходным словом (то есть не потерялись буквы), потом остальные.
     Каждая лемма уходит в KSAA (кэш data/quality/ksaa_analyzer_cache).

  2. ФИЛЬТР. Однословные статьи предпочтительнее составных; статья-имя
     собственное отбрасывается, если есть обычное слово. Применяется внутри
     каждого словаря до голосования.

  3. КРОСС-ПОДТВЕРЖДЕНИЕ. Один ответ на словарь. Пара из разных словарей
     подтверждает друг друга: заголовок совпал +2, корень совпал +1.
     Побеждает максимум подтверждений; при равенстве — KSAA (арабское толкование).

Выход: data/quality/pipeline_full.json + печать изменений для разметки.

Запуск:
    python3 tools/pipeline_full.py [--print]
"""
import argparse
import json
import os
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QDIR = os.path.join(HERE, "data", "quality")
ACACHE = os.path.join(QDIR, "ksaa_analyzer_cache")
API = "https://siwar.ksaa.gov.sa/api/riyadh/Result/SearchEntriesWeb?word="

DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u0640]")


def nk(s):
    s = DIAC.sub("", unicodedata.normalize("NFC", s or ""))
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = s.replace("ة", "ه")
    return re.sub(r"[^\u0600-\u06FF]", "", s)


def lemma_key(s):
    s = nk(s)
    for p in ("وال", "بال", "كال", "فال"):
        if s.startswith(p) and len(s) > 5:
            s = s[3:]
            break
    if s.startswith("ال") and len(s) > 4:
        s = s[2:]
    return s


def norm_root(s):
    s = nk(s).replace("ء", "")
    return s if len(s) >= 2 else ""


def fetch(word):
    os.makedirs(ACACHE, exist_ok=True)
    p = os.path.join(ACACHE, nk(word) + ".json")
    if os.path.exists(p):
        try:
            return json.load(open(p, encoding="utf-8"))
        except Exception:
            return []
    try:
        req = urllib.request.Request(API + urllib.parse.quote(word), headers={"User-Agent": "Mozilla/5.0"})
        d = json.loads(urllib.request.urlopen(req, timeout=25).read())
        json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False)
        time.sleep(0.12)
        return d
    except Exception:
        return []


def summ(e):
    se = e.get("resultSenseDTO") or []
    f = se[0] if se else {}
    d = f.get("definition") or ""
    return {"head": e.get("unDiacWord") or "", "def": d[:300],
            "pos": e.get("mainPOS") or "",
            "root": norm_root(f.get("root") or ""),
            "single": " " not in (e.get("unDiacWord") or "").strip(),
            "quranic": "قرآني" in d,
            "passive": d.startswith("فعل ماض مبني للمجهول"),
            "proper": (e.get("subPOS") or "").startswith("علم") or "علم" in (e.get("subPOS") or ""),
            "en": (f.get("englishTranslation") or "").strip()}


# ---------------------------------------------------------------- 1. анализатор
def analyzer_lemmas(word, limit=2):
    """Леммы от AlKhalil. Сначала чтение, чья основа не потеряла букв."""
    try:
        from farahidi import analyze
    except Exception:
        return []
    try:
        res = analyze(word)
    except Exception:
        return []
    target = lemma_key(word)
    scored = []
    for i, a in enumerate(res):
        d = a.to_dict() if hasattr(a, "to_dict") else dict(a)
        lem = d.get("lemma") or ""
        stem = lemma_key(d.get("stem") or "")
        if not lem:
            continue
        exact = 0 if stem == target else 1
        pos = 0 if d.get("pos") in ("اسم", "فعل") else 1
        scored.append((exact, pos, i, lem, d.get("root") or ""))
    scored.sort()
    out, seen = [], set()
    for exact, pos, i, lem, root in scored:
        k = lemma_key(lem)
        if k in seen:
            continue
        seen.add(k)
        out.append({"lemma": lem, "key": k, "root": norm_root(root), "exact": exact == 0})
        if len(out) >= limit:
            break
    return out


# ------------------------------------------------------------------- 2. фильтр
def pick_entry(entries, lemma, strict=True):
    """Выбор статьи внутри словаря: однословные, не коранические, не пассив,
    не имя собственное (если есть альтернатива); точное совпадение с леммой — бонус."""
    q = lemma_key(lemma) if lemma else ""
    best = None
    for i, s in enumerate(entries):
        if strict and not s["single"]:
            continue
        sc = 0.0
        h = lemma_key(s["head"])
        if q and h == q:
            sc += 100
        elif q and (h.startswith(q) or q.startswith(h)):
            sc += 15
        if s["proper"]:
            sc -= 40
        if s["quranic"]:
            sc -= 20
        if s["passive"]:
            sc -= 15
        if s["en"]:
            sc += 3
        sc -= i * 0.5
        if best is None or sc > best[0]:
            best = (sc, s)
    if best is None and strict:
        return pick_entry(entries, lemma, strict=False)
    return best


# ------------------------------------------------------------- 3. подтверждение
FAMILY = {"KSAA": "ksaa", "PIPE": "ksaa", "ANALYZER": "ksaa", "KAIKKI": "kaikki", "WEHR": "wehr"}
INTRA = {"KSAA": 0, "ANALYZER": 1, "PIPE": 2, "KAIKKI": 0, "WEHR": 0}
FAM_PRIORITY = {"ksaa": 0, "kaikki": 1, "wehr": 2}   # при равенстве подтверждений — KSAA


def agree(fa, a, fb, b):
    if fa == fb or not a or not b:
        return 0
    if lemma_key(a) == lemma_key(b):
        return 2
    if a == b:
        return 1
    return 0


def support_of(src, cand, fams):
    """Сколько внешних подтверждений у ответа данной стадии."""
    sup, wit = 0, []
    for f, oc in fams.items():
        if f == FAMILY[src]:
            continue
        s = agree(FAMILY[src], cand["head"], f, oc["head"])
        if s == 0 and cand.get("root") and oc.get("root") and cand["root"] == oc["root"]:
            s = 1
        if s:
            sup += s
            wit.append(f)
    return sup, wit


def decide(ksaa_answers, other):
    """
    ksaa_answers — ответы словаря KSAA с приоритетом [прямой, анализатор, пайплайн];
    other        — по одному ответу от Kaikki и Wehr.
    Внутри KSAA побеждает ответ, подтверждённый снаружи; при равенстве — прямой запрос.
    Затем — обычное голосование между словарями.
    """
    fams_other = {FAMILY[k]: v for k, v in other.items() if v}
    scored = []
    for src, cand in ksaa_answers:
        sup, wit = support_of(src, cand, fams_other)
        scored.append(((sup, -INTRA[src]), src, cand, sup, wit))
    scored.sort(key=lambda t: t[0], reverse=True)
    if not scored:
        # словарь KSAA молчит — голосуем только между остальными
        rest = [((*support_of(k, v, fams_other), -INTRA[k]), k, v) for k, v in other.items() if v]
        if not rest:
            return None
        rest.sort(key=lambda t: (t[0][0], -FAM_PRIORITY[FAMILY[t[1]]], t[0][1]), reverse=True)
        sup, wit, src, cand = rest[0][0][0], rest[0][0][1], rest[0][1], rest[0][2]
        return src, cand, sup, wit
    best_ksaa = scored[0]
    fams = dict(fams_other)
    fams["ksaa"] = {"head": best_ksaa[2]["head"], "root": best_ksaa[2].get("root", "")}

    # итоговое голосование между словарями
    final = []
    for src, cand, sup, wit in [(best_ksaa[1], best_ksaa[2], best_ksaa[3], best_ksaa[4])] + \
                               [(k, v, *support_of(k, v, fams)) for k, v in other.items() if v]:
        final.append(((sup, -FAM_PRIORITY[FAMILY[src]], -INTRA[src]), src, cand, sup, wit))
    final.sort(key=lambda t: t[0], reverse=True)
    r, src, cand, sup, wit = final[0]
    return src, cand, sup, wit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", dest="do_print", action="store_true")
    ap.add_argument("--no-analyzer", action="store_true")
    ap.add_argument("--no-filter", action="store_true")
    ap.add_argument("--out", default="pipeline_full.json")
    a = ap.parse_args()

    # базовые кандидаты из прошлых прогонов
    import importlib.util
    spec = importlib.util.spec_from_file_location("c", os.path.join(HERE, "tools", "crossrank.py"))
    cr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cr)
    base = {d["idx"]: d for d in cr.load_candidates()}
    kaikki_pos = {r["idx"]: (r.get("pos") or "")
                  for r in json.load(open(os.path.join(QDIR, "kaikki200.json"), encoding="utf-8"))}
    for i, d in base.items():
        if "KAIKKI" in d["cands"]:
            d["cands"]["KAIKKI"]["proper"] = kaikki_pos.get(i) in ("name", "prop", "proper-noun")

    rows = [json.loads(l) for l in open(os.path.join(QDIR, "golden200.jsonl"), encoding="utf-8")]

    # леммы анализатора и запросы в KSAA
    plans = {}
    for i, r in enumerate(rows, 1):
        plans[i] = analyzer_lemmas(r["word"])
    need = {p["lemma"] for ps in plans.values() for p in ps}
    todo = [l for l in need if not os.path.exists(os.path.join(ACACHE, nk(l) + ".json"))]
    print(f"лемм анализатора: {len(need)}, новых запросов в KSAA: {len(todo)}")
    with ThreadPoolExecutor(max_workers=6) as p:
        list(p.map(fetch, todo))

    out = []
    for i, r in enumerate(rows, 1):
        src_base = base[i]["cands"]
        ksaa_answers = []
        # прямой запрос: пересобираем ответ из кэша строгим фильтром
        cdir = src_base.get("KSAA")
        if cdir:
            ksaa_answers.append(("KSAA", cdir))
        # стадия 1: анализатор
        for p in ([] if a.no_analyzer else plans[i]):
            entries = [summ(e) for e in (fetch(p["lemma"]) or [])][:12]
            if not entries:
                continue
            pick = pick_entry(entries, p["lemma"])
            if pick:
                ksaa_answers.append(("ANALYZER", {"head": pick[1]["head"], "text": pick[1]["def"],
                                                  "root": pick[1]["root"] or p["root"],
                                                  "lemma": p["lemma"]}))
                break
        if "PIPE" in src_base:
            ksaa_answers.append(("PIPE", src_base["PIPE"]))
        # стадия 2: фильтр имён собственных в Kaikki
        other = {}
        if "KAIKKI" in src_base and (a.no_filter or not src_base["KAIKKI"].get("proper")):
            other["KAIKKI"] = src_base["KAIKKI"]
        if "WEHR" in src_base:
            other["WEHR"] = src_base["WEHR"]

        res = decide(ksaa_answers, other)
        if not res:
            out.append({"idx": i, "word": r["word"], "src": None, "head": None,
                        "text": None, "support": 0, "witnesses": []})
            continue
        src, cand, sup, wit = res
        out.append({"idx": i, "word": r["word"], "src": src, "head": cand["head"],
                    "text": (cand.get("text") or "")[:200], "support": sup,
                    "witnesses": wit, "root": cand.get("root", "")})

    json.dump(out, open(os.path.join(QDIR, a.out), "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)

    # сравнение с прошлым кросс-прогоном
    prev = {p["idx"]: p for p in json.load(open(os.path.join(QDIR, "crossrank200.json"), encoding="utf-8"))}
    changed = [o for o in out
               if (o["head"] or "") != (prev[o["idx"]].get("head") or "")]
    print(f"решений изменилось против кросс-прогона: {len(changed)}")
    if a.do_print:
        for o in changed:
            print(f"{o['idx']:3d} {o['word']:12s} {o['src'] or '—':8s} +{o['support']} "
                  f"{str(o['head'] or '—'):13s} {(o['text'] or '')[:64]}")
    print("файл:", os.path.join(QDIR, a.out))


if __name__ == "__main__":
    main()
