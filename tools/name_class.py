#!/usr/bin/env python3
"""Класс слова: словарное / форма с клитикой / имя собственное.

  1. CAMeL (calima-msa-r13) дал разбор → обычное слово.
  2. Разбора нет → пробуем разложить на клитику и основу (ا و ف ب ك ل ال أ / ني ها ه ك هم نا ون ين ات وا ت ي)
     и проверяем три условия: основа есть в словаре (KSAA), CAMeL её разбирает,
     и аффикс морфологически совместим с её частью речи.
        • глагольное окончание (ي ون ين ات وا ت) требует основы-глагола И показателя
          (ا/أ в начале) — «اخرجي» = ا+خرج+ي, а «ماتي» так не читается;
        • притяжательное/объектное (ني ه ها هم نا ك) требует имени, прилагательного или глагола.
  3. Ничего не подошло → «похоже на имя собственное, статьи нет».
"""
import json, os, re, time, urllib.request, urllib.parse

HAR = re.compile(r"[\u064B-\u0652\u0670\u0640]")
un = lambda s: HAR.sub("", s or "")
def key(s):
    return (un(s).replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه").replace("ـ", ""))

PRE = ["وال", "بال", "كال", "فال", "لل", "ال", "ها", "أ", "و", "ف", "ب", "ك", "ل"]
SUF = ["هما", "كما", "هم", "هن", "ها", "نا", "ني", "ون", "ين", "ات", "وا", "ه", "ك", "ت", "ي"]
VERB_SUF = {"ي", "ون", "ين", "ات", "وا", "ت"}
POSS_SUF = {"ني", "ه", "ها", "هم", "هن", "نا", "ك", "كما", "هما"}
WEAK = ["", "ا", "و", "ي", "ى", "ة"]

API = "https://siwar.ksaa.gov.sa/api/riyadh/Result/SearchEntriesWeb?word="
CACHE = os.path.join("data", "quality", "lemma_cache")

_db = None
def analyzer():
    global _db
    if _db is None:
        from camel_tools.morphology.database import MorphologyDB
        from camel_tools.morphology.analyzer import Analyzer
        _db = Analyzer(MorphologyDB.builtin_db("calima-msa-r13"))
    return _db

def analyses(word):
    return analyzer().analyze(un(word))

def lemmas(word):
    seen = {}
    for x in analyses(word):
        l = un(x.get("lex") or "")
        if l:
            p = x.get("lex_logprob", -99)
            if l not in seen or p > seen[l]:
                seen[l] = p
    return [l for l, _ in sorted(seen.items(), key=lambda kv: -kv[1])]

def pos_of(word):
    return {x.get("pos") for x in analyses(word)}

def ksaa_heads(word):
    """Головы статей KSAA для этой словоформы (сеть, с кэшем)."""
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, re.sub(r"[^\w\u0621-\u064A]", "_", word) + ".json")
    if os.path.exists(p):
        try: d = json.load(open(p, encoding="utf-8"))
        except Exception: return set()
    else:
        try:
            req = urllib.request.Request(API + urllib.parse.quote(word), headers={"User-Agent": "Mozilla/5.0"})
            d = json.loads(urllib.request.urlopen(req, timeout=20).read())
            json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False)
            time.sleep(0.12)
        except Exception:
            return set()
    return {key(e.get("unDiacWord") or "") for e in d if e.get("unDiacWord")}

def decompose(word):
    w = un(word); out = []
    for pre in [""] + PRE:
        if pre and not w.startswith(pre):
            continue
        rest = w[len(pre):]
        if len(rest) < 2:
            continue
        for suf in [""] + SUF:
            if suf and not rest.endswith(suf):
                continue
            stem = rest[:len(rest) - len(suf)] if suf else rest
            if len(stem) < 2:
                continue
            # слабую букву восстанавливаем только там, где она морфологически возможна:
            # «أعط» + ي = أعطي при объектном ـني; но «كوت» + ا при ـون — уже нет
            weaks = WEAK if (suf in POSS_SUF or suf == "") else [""]
            for wk in weaks:
                out.append((pre, stem + wk, suf))
    return list(dict.fromkeys(out))

def classify(word, verbose=False):
    lm = lemmas(word)
    if lm:
        return {"class": "word", "lemmas": lm[:4]}
    for pre, base, suf in decompose(word):
        if not base:
            continue
        pos = pos_of(base)
        if not pos:
            continue
        heads = ksaa_heads(base)
        # служебные основы (هو، هذا، من) словарной статьи часто не имеют — им словарь не нужен
        if key(base) not in heads and not (pos & {"pron", "part", "conj", "prep", "det", "interj"}):
            continue
        if suf in VERB_SUF and not ("verb" in pos and pre in {"ا", "أ", ""}):
            continue
        if suf in POSS_SUF and not (pos & {"noun", "adj", "verb", "pron"}):
            continue
        bl = lemmas(base)
        return {"class": "clitic", "base": base, "lemmas": (bl or [base])[:3],
                "pre": pre, "suf": suf}
    return {"class": "name", "lemmas": []}

if __name__ == "__main__":
    for w in ["اخرجي", "أعطني", "أهذا", "هاهو", "جاكي", "ماتي", "تومي", "آندي", "ديكستر", "كوتون", "فرانك"]:
        print(f"{w:10s} → {classify(w)}")
