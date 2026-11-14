#!/usr/bin/env python3
"""Пайплайн v2: класс имени -> CAMeL-леммы -> проба KSAA -> набор статей для попапа.

Правила:
  • CAMeL не дал разбора → ответ «похоже на имя собственное, статьи нет»;
  • иначе в попап идут: ответ текущего пайплайна (v1) + первая статья KSAA по каждой из
    двух старших лемм CAMeL (если голова отличается). Максимум 3 статьи, все смыслы видны;
  • v1-ответ всегда остаётся в наборе — верные ответы не вытесняются.
"""
import json, os, re, sys, time, urllib.request, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from tools.name_class import classify

API = "https://siwar.ksaa.gov.sa/api/riyadh/Result/SearchEntriesWeb?word="
CACHE = os.path.join("data", "quality", "lemma_cache")
HAR = re.compile(r"[\u064B-\u0652\u0670\u0640]")
un = lambda s: HAR.sub("", s or "")
def key(s):
    return (un(s).replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه").replace("ـ", ""))

def kq(w):
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, re.sub(r"[^\w\u0621-\u064A]", "_", w) + ".json")
    if os.path.exists(p):
        try: return json.load(open(p, encoding="utf-8"))
        except Exception: return []
    try:
        req = urllib.request.Request(API + urllib.parse.quote(w), headers={"User-Agent": "Mozilla/5.0"})
        d = json.loads(urllib.request.urlopen(req, timeout=20).read())
        json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False)
        time.sleep(0.12)
        return d
    except Exception:
        return []

def entry_view(e):
    ss = [s for s in (e.get("resultSenseDTO") or []) if (s.get("definition") or "").strip()]
    return {"head": e.get("unDiacWord") or "", "pos": e.get("mainPOS") or "",
            "senses": [s["definition"].strip() for s in ss][:4],
            "en": (ss[0].get("englishTranslation") or "") if ss else "",
            "n_senses": len(ss)}

def main():
    pf = json.load(open("data/quality/pf_full.json", encoding="utf-8"))
    out = []
    for r in pf:
        w = r["word"]
        c = classify(w)
        rec = {"idx": r["idx"], "word": w, "class": c["class"], "lemmas": c.get("lemmas", [])}
        if c["class"] == "name":
            rec["label"] = "اسم علم"
            rec["articles"] = []
            out.append(rec); continue
        v1 = {"head": r["head"], "src": r["src"], "senses": [r["text"] or ""], "rank": "v1"}
        arts = [v1]
        seen = {(key(v1["head"]), key((v1["senses"][0] or "")[:24]))}
        for lm in c["lemmas"][:2]:
            for e in kq(lm)[:3]:
                v = entry_view(e)
                if not v["senses"]:
                    continue
                k = (key(v["head"]), key(v["senses"][0][:24]))
                if k in seen:
                    continue
                v["rank"] = f"лемма {lm}"
                arts.append(v); seen.add(k)
                if len(arts) >= 5:
                    break
            if len(arts) >= 5:
                break
        rec["articles"] = arts[:5]
        out.append(rec)
    json.dump(out, open("data/quality/pf_v2.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    from collections import Counter
    print("слов:", len(out), "| классы:", dict(Counter(x["class"] for x in out)))
    print("статей в попапе:", dict(Counter(len(x["articles"]) for x in out if x["class"] == "word")))
    print("имён (ответ «имя собственное»):", [x["word"] for x in out if x["class"] == "name"])

if __name__ == "__main__":
    main()
