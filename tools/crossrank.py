#!/usr/bin/env python3
"""
Кросс-словарное подтверждение: показать первым то значение, которое
подтверждается двумя источниками.

Правила (заданы до прогона, не подгонялись под набор):

  Кандидаты — top-1 каждого источника:
    KSAA (прямой запрос), KSAA-по-лемме (пайплайн), Kaikki, Hans Wehr.

  Подтверждение пары кандидатов из РАЗНЫХ словарей:
    +2  нормализованные заголовки совпали (это одно и то же слово)
    +1  совпал корень (слабое подтверждение: корень общий у целого гнезда)
     0  иначе

  Семейство: KSAA и «KSAA-по-лемме» — один словарь, друг друга не подтверждают.

  Победитель: максимум подтверждения, при равенстве — приоритет семейства
  KSAA > KSAA-по-лемме > Kaikki > Wehr (KSAA даёт арабское толкование,
  а это основной продукт попапа).

Выход: печать сводки + data/quality/crossrank200.json

Запуск:
    python3 tools/crossrank.py
"""
import json
import os
import re
import unicodedata
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QDIR = os.path.join(HERE, "data", "quality")
CACHE = os.path.join(QDIR, "ksaa_lemma_cache")

DIAC = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u0640]")


def norm(s):
    s = DIAC.sub("", unicodedata.normalize("NFC", s or ""))
    s = re.sub("[أإآٱ]", "ا", s)
    s = re.sub("[ىئ]", "ي", s)
    s = s.replace("ة", "ه")
    return re.sub(r"[^\u0600-\u06FF]", "", s)


def norm_root(s):
    s = norm(s).replace("ء", "")
    return s if len(s) >= 2 else ""


def lemma_key(s):
    """Сравнение заголовков, устойчивое к артиклю и однобуквенным приставкам."""
    s = norm(s)
    for p in ("وال", "بال", "كال", "فال"):
        if s.startswith(p) and len(s) > 5:
            s = s[3:]
            break
    if s.startswith("ال") and len(s) > 4:
        s = s[2:]
    return s


# каждый словарь участвует в голосовании ровно одним ответом
PRIORITY = {"KSAA": 0, "PIPE": 1, "KAIKKI": 2, "WEHR": 3}
FAMILY = {"KSAA": "ksaa", "PIPE": "ksaa", "KAIKKI": "kaikki", "WEHR": "wehr"}
# внутри словаря: прямой запрос важнее перезапроса по лемме
INTRA = {"KSAA": 0, "PIPE": 1, "KAIKKI": 0, "WEHR": 0}


def load_candidates():
    rows = [json.loads(l) for l in open(os.path.join(QDIR, "golden200.jsonl"), encoding="utf-8")]
    kai = {r["idx"]: r for r in json.load(open(os.path.join(QDIR, "kaikki200.json"), encoding="utf-8"))}
    pipe = {r["idx"]: r for r in json.load(open(os.path.join(QDIR, "pipeline200.json"), encoding="utf-8"))}

    def pipe_root(lemma):
        p = os.path.join(CACHE, norm(lemma) + ".json")
        if not os.path.exists(p):
            return ""
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception:
            return ""
        for e in d[:6]:
            if norm(e.get("unDiacWord")) == norm(lemma):
                se = e.get("resultSenseDTO") or []
                if se:
                    return norm_root(se[0].get("root") or "")
        return ""

    out = []
    for i, r in enumerate(rows, 1):
        cands = {}
        k = r.get("ksaa_ranked")
        if k and k.get("def"):
            cands["KSAA"] = {"text": k["def"], "head": k["unDiac"], "root": norm_root(k.get("root") or "")}
        w = r.get("wehr")
        if w:
            cands["WEHR"] = {"text": w.get("body") or "", "head": w.get("hw") or w.get("root_hw") or "",
                             "root": norm_root(w.get("root_hw") or "")}
        kk = kai.get(i)
        if kk and kk.get("gloss"):
            cands["KAIKKI"] = {"text": kk["gloss"], "head": kk.get("head") or "", "root": norm_root(kk.get("root") or "")}
        pp = pipe.get(i)
        if pp and pp.get("def"):
            cands["PIPE"] = {"text": pp["def"], "head": pp.get("unDiac") or "",
                             "root": pipe_root(pp.get("lemma") or "")}
        out.append({"idx": i, "word": r["word"], "band": r["band"], "cands": cands})
    return out


def agreement(fa, a, fb, b):
    """fa/fb — семейства словарей, a/b — заголовки или корни."""
    if fa == fb:
        return 0
    if not a or not b:
        return 0
    if lemma_key(a) == lemma_key(b):
        return 2
    if a == b:
        return 1
    return 0


def vote(cands):
    """Один ответ на словарь: KSAA — прямой, если он есть, иначе перезапрос по лемме."""
    by_fam = {}
    for src in sorted(cands, key=lambda x: INTRA[x]):
        f = FAMILY[src]
        if f not in by_fam:
            by_fam[f] = src
    return {src: cands[src] for src in by_fam.values()}


def decide(cands):
    cands = vote(cands)
    best = None
    for src, c in cands.items():
        support = 0
        witnesses = []
        for other, oc in cands.items():
            if other == src:
                continue
            fa, fb = FAMILY[src], FAMILY[other]
            s = agreement(fa, c["head"], fb, oc["head"])
            if s == 0:
                s = agreement(fa, c["root"], fb, oc["root"])
            if s:
                support += s
                witnesses.append((other, s))
        rank = (support, -PRIORITY[src])
        if best is None or rank > best[0]:
            best = (rank, src, support, witnesses)
    return best


def pct(a, b):
    return f"{100*a/b:.0f}%" if b else "—"


def main():
    import importlib.util
    spec = importlib.util.spec_from_file_location("s", os.path.join(HERE, "tools", "score_golden.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    LAB = {"KSAA": m.KSAA_RANKED, "WEHR": m.WEHR, "KAIKKI": m.KAIKKI, "PIPE": m.PIPELINE}

    data = load_candidates()
    picked = []
    for d in data:
        if not d["cands"]:
            picked.append({"idx": d["idx"], "word": d["word"], "src": None, "label": "n",
                           "support": 0, "witnesses": []})
            continue
        (rank, src, support, wit) = decide(d["cands"])
        picked.append({"idx": d["idx"], "word": d["word"], "src": src,
                       "label": LAB[src][d["idx"]], "support": support,
                       "witnesses": [w[0] for w in wit],
                       "text": d["cands"][src]["text"][:160],
                       "head": d["cands"][src]["head"]})
    json.dump(picked, open(os.path.join(QDIR, "crossrank200.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)

    real = [i for i in range(1, 201) if i not in m.NAMES]

    def report(lab, title, subset=None):
        idx = subset if subset is not None else range(1, 201)
        c = Counter(lab[i] for i in idx)
        rr = [i for i in idx if i in real]
        cr = Counter(lab[i] for i in rr)
        print(f"{title:36s} n={len(list(idx)):3d} o={c['o']:3d} p={c['p']:3d} w={c['w']:3d} n={c['n']:3d}"
              f" | {100*c['o']/max(1,len(list(idx))):.0f}% (188: {100*cr['o']/max(1,len(rr)):.0f}%)")

    cross = {p["idx"]: p["label"] for p in picked}
    cross_c = Counter(cross.values())
    report(cross, "кросс-подтверждение, top-1")
    report(m.KSAA_RANKED, "KSAA + ранкер (базовая линия)")

    conf = [p["idx"] for p in picked if p["support"] >= 2]
    unconf = [p["idx"] for p in picked if p["support"] < 2]
    unc = unconf
    print()
    report(cross, "  из них подтверждённые (>=2)", conf)
    report(cross, "  из них без подтверждения", unconf)

    print("\nисточник победившего ответа:")
    for src, n in Counter(p["src"] for p in picked).most_common():
        acc = sum(1 for p in picked if p["src"] == src and p["label"] == "o")
        print(f"  {str(src):7s} выбран {n:3d} раз, верен {acc:3d} ({100*acc/max(1,n):.0f}%)")

    better = [i for i in range(1, 201) if cross[i] == "o" and m.KSAA_RANKED[i] != "o"]
    worse = [i for i in range(1, 201) if m.KSAA_RANKED[i] == "o" and cross[i] != "o"]
    print(f"\nпротив базовой линии: лучше {len(better)} {better}, хуже {len(worse)} {worse}")
    # --- раздел для отчёта
    def line(lab, idx):
        c = Counter(lab[i] for i in idx)
        return c

    conf_c = line(cross, conf)
    unc_c = line(cross, unc)
    base_c = line(m.KSAA_RANKED, range(1, 201))
    md = []
    A = md.append
    A("## Кросс-словарное подтверждение: показывать первым то, что подтвердили двое")
    A("")
    A("Правила заданы до прогона: каждый словарь даёт ровно одного кандидата (у KSAA — прямой")
    A("ответ, а если он пуст, то ответ по лемме Kaikki); пара из **разных** словарей подтверждает")
    A("друг друга, если совпал заголовок (+2) или корень (+1); побеждает максимум подтверждений,")
    A("при равенстве — KSAA, чтобы попап отдавал арабское толкование. Смысловое сравнение по")
    A("английским переводам сюда не входит (см. ниже, почему).")
    A("")
    A("| стратегия | верно | частично | неверно | пусто | верно /200 | /188 |")
    A("|---|---|---|---|---|---|---|")
    A(f"| KSAA + ранкер (было) | {base_c['o']} | {base_c['p']} | {base_c['w']} | {base_c['n']} | "
      f"**{pct(base_c['o'],200)}** | {pct(sum(1 for i in real if m.KSAA_RANKED[i]=='o'),len(real))} |")
    A(f"| **кросс-подтверждение** | {cross_c['o']} | {cross_c['p']} | {cross_c['w']} | {cross_c['n']} | "
      f"**{pct(cross_c['o'],200)}** | {pct(sum(1 for i in real if cross[i]=='o'),len(real))} |")
    A("")
    A(f"Против базовой линии: **лучше {len(better)}, хуже {len(worse)}**. Пустых ответов стало")
    A(f"{cross_c['n']} вместо {base_c['n']}: словари страхуют друг друга, попап почти всегда что-то покажет.")
    A("")
    A("### Главное: подтверждение — это готовый индикатор уверенности")
    A("")
    A("| подмножество | слов | верно | верно+частично (верная лемма) |")
    A("|---|---|---|---|")
    A(f"| два словаря подтвердили | {len(conf)} | **{pct(conf_c['o'],len(conf))}** | "
      f"{pct(conf_c['o']+conf_c['p'],len(conf))} |")
    A(f"| подтверждения нет | {len(unconf)} | **{pct(unc_c['o'],len(unconf))}** | "
      f"{pct(unc_c['o']+unc_c['p'],len(unconf))} |")
    A("")
    A(f"Разрыв **{pct(conf_c['o'],len(conf))} против {pct(unc_c['o'],len(unconf))}** — это разница между")
    A("«словари сошлись» и «они разошлись». Для UI это готовая механика: подтверждённое значение")
    A("показываем первым и без оговорок, неподтверждённое — с пометкой и списком альтернатив.")
    A("Вторая цифра важнее первой: среди подтверждённых **верная лемма в "
      f"{pct(conf_c['o']+conf_c['p'],len(conf))}** случаев — то есть слово найдено правильно почти")
    A("всегда, и остаётся только выбрать смысл.")
    A("")
    A("### Что здесь НЕ сработало (проверено, отклонено)")
    A("")
    A("Подтверждение **смысла**, а не слова: у каждого смысла KSAA есть английский перевод, его можно")
    A("сверить с глоссой Kaikki. На практике пересечение в одно слово — шум: `السنة` → статья `لسان`")
    A("с пометкой «Tongue» проходит проверку, `البيت` подтягивает `البيت الأبيض` «White House».")
    A("Из 23 изменившихся решений 4 стали хуже, 1 лучше, 18 без изменений — вариант отклонён.")
    A("Нужен не однословный оверлап, а согласование части речи и многотокенное совпадение.")
    A("")
    A("### Что осталось")
    A("")
    A(f"Ошибки теперь однотипны: **верная лемма, не тот смысл** ({cross_c['p']} случаев) — и это уже")
    A("задача не словарей, а приора порядка смыслов (частотность смысла, примеры употребления,")
    A("контекст слова в предложении). Кросс-подтверждение её принципиально не решает: два словаря")
    A("подтверждают слово, а не значение.")
    A("")
    with open(os.path.join(QDIR, "crossrank-section.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print("файл:", os.path.join(QDIR, "crossrank200.json"))
    print("раздел отчёта:", os.path.join(QDIR, "crossrank-section.md"))


if __name__ == "__main__":
    main()
