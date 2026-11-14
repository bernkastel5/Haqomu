#!/usr/bin/env python3
"""
Инспекция Hans Wehr SQLite (assets/hanswehr.sqlite из GibreelAbdullah/HansWehrDictionary).

Проверяет: схему, объём, дубликаты, иерархию корней, качество транслитерации,
структурные маркеры (породы, идиомы, множественные), мусор OCR, кораническую таблицу,
пригодность для поиска по словоформам.

Запуск:
    python3 tools/hw_inspect.py /path/to/hanswehr.sqlite
    python3 tools/hw_inspect.py /path/to/hanswehr.sqlite --resolve   # + проверка алгоритма привязки корней

Только чтение, ничего не изменяет.
"""
import sqlite3
import re
import sys
import os
import bisect
import collections

DIACRITICS = re.compile(r'[\u0610-\u061A\u064B-\u065F\u0670\u0640]')
INDONESIAN = re.compile(r'\b(dan|atau|tidak|dengan|yang|ini|itu|dari|untuk|karena|Agustus|apakah)\b')
VERB_FORM = re.compile(r'<b>(I|II|III|IV|V|VI|VII|VIII|IX|X)</b>')
STRAY_NUM = re.compile(r'(?<![\w])\d{2,4}(?![\w])')
LATIN = re.compile(r'[A-Za-z]')


def strip_diacritics(s):
    return DIACRITICS.sub('', s or '')


def root_letters_in_word(word, root):
    """Все буквы корня встречаются в слове в правильном порядке (инвариант арабской деривации)."""
    w, r = strip_diacritics(word), strip_diacritics(root)
    if not r:
        return False
    it = iter(w)
    return all(ch in it for ch in r)


def main(path, resolve=False):
    if not os.path.exists(path):
        sys.exit(f"нет файла: {path}")
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    cur = con.cursor()

    print("=" * 70)
    print("ФАЙЛ")
    print("=" * 70)
    size = os.path.getsize(path)
    cur.execute("pragma page_size")
    ps = cur.fetchone()[0]
    cur.execute("pragma page_count")
    pc = cur.fetchone()[0]
    cur.execute("pragma freelist_count")
    fl = cur.fetchone()[0]
    print(f"размер: {size / 1048576:.1f} МБ   page_size={ps}   pages={pc}")
    print(f"свободных страниц: {fl} ({fl * ps / 1048576:.1f} МБ) -> после VACUUM ~{(size - fl * ps) / 1048576:.1f} МБ")

    rows = list(cur.execute("select id, c1, c2, c3, c4 from DICTIONARY_content"))
    print(f"\nзаписей: {len(rows)}")
    print(f"  is_root=1: {sum(1 for r in rows if r[3] == 1)}   is_root=0: {sum(1 for r in rows if r[3] == 0)}")

    print("\n" + "=" * 70)
    print("ДУБЛИКАТЫ СЛОВ")
    print("=" * 70)
    cnt = collections.Counter(r[1] for r in rows)
    dups = {w: n for w, n in cnt.items() if n > 1}
    print(f"уникальных слов: {len(cnt)}   задублировано: {len(dups)}   лишних строк: {sum(n - 1 for n in dups.values())}")
    print("топ:", sorted(dups.items(), key=lambda kv: -kv[1])[:6])

    print("\n" + "=" * 70)
    print("СТРУКТУРНЫЕ МАРКЕРЫ И МУСОР")
    print("=" * 70)
    defs = [(r[1], r[2] or '') for r in rows]
    checks = {
        'породы <b>I..X</b>': VERB_FORM,
        'идиомы «│»': None,
        'множественные «pl.»': re.compile(r'\bpl\.'),
        'отсылки «see X»': re.compile(r'\bsee\b'),
        'номера страниц (2-4 цифры)': STRAY_NUM,
        'индонезийские вкрапления': INDONESIAN,
        'нераскрытые шаблоны': re.compile(r'\{\{|\}\}'),
        'кириллица': re.compile(r'[а-яА-Я]'),
    }
    for name, rx in checks.items():
        if rx is None:
            n = sum(1 for _, d in defs if '│' in d)
        else:
            n = sum(1 for _, d in defs if rx.search(d))
        print(f"  {name:32s} {n:6d}  ({100 * n / len(defs):.1f}%)")
    print(f"  {'пустые определения':32s} {sum(1 for _, d in defs if not d.strip()):6d}")

    lengths = sorted(len(d) for _, d in defs)
    print(f"\nдлина определения: min={lengths[0]} p10={lengths[len(lengths)//10]} "
          f"median={lengths[len(lengths)//2]} p90={lengths[9*len(lengths)//10]} max={lengths[-1]}")

    print("\n" + "=" * 70)
    print("ТРАНСЛИТЕРАЦИЯ (ожидается: нерабочая)")
    print("=" * 70)
    tr = dict(cur.execute("select id, transliteration from TRANSLITERATION"))
    print(f"строк: {len(tr)}, пустых: {sum(1 for v in tr.values() if not (v or '').strip())}")
    by_id = {r[0]: r for r in rows}
    bad = 0
    total = 0
    for wid in range(1, 2000):
        r, t = by_id.get(wid), tr.get(wid)
        if not r or not t:
            continue
        tokens = (r[2] or '').split()
        if len(tokens) < 2:
            continue
        total += 1
        if not tokens[1].lower().startswith(t[:2]):
            bad += 1
    print(f"расхождений с романизацией внутри статьи: {bad}/{total} ({100 * bad / max(total, 1):.0f}%)")
    for wid in (18380, 18389, 18390):
        r = by_id.get(wid)
        if r:
            print(f"  {wid} {r[1]:10s} таблица={tr.get(wid)!r:10s} в статье={(r[2] or '').split()[:2]}")

    print("\n" + "=" * 70)
    print("ИЕРАРХИЯ КОРНЕЙ")
    print("=" * 70)
    roots = sorted(r[0] for r in rows if r[3] == 1)
    stored_ok = stored_tot = 0
    for r in rows:
        if r[3] != 0:
            continue
        p = by_id.get(r[4])
        if p is None or p[3] != 1:
            continue
        stored_tot += 1
        stored_ok += root_letters_in_word(r[1], p[1])
    print(f"как хранится parent_id: {stored_ok}/{stored_tot} = "
          f"{100 * stored_ok / max(stored_tot, 1):.1f}% (буквы корня внутри слова)")

    if resolve:
        fixed_ok = fixed_tot = 0
        for r in rows:
            if r[3] != 0:
                continue
            i = bisect.bisect_left(roots, r[0]) - 1
            if i < 0:
                continue
            fixed_tot += 1
            fixed_ok += root_letters_in_word(r[1], by_id[roots[i]][1])
        print(f"ближайший предшествующий корень: {fixed_ok}/{fixed_tot} = "
              f"{100 * fixed_ok / max(fixed_tot, 1):.1f}%  <-- рекомендуемое правило")

    bad_roots = [r[1] for r in rows if r[3] == 1 and (' ' in r[1] or len(r[1]) > 5 or not re.fullmatch(r'[\u0600-\u06FF ]+', r[1]))]
    print(f"\nсомнительных «корней» (пробел / >5 букв / неарабские): {len(bad_roots)}")
    print("  примеры:", bad_roots[:10])

    print("\n" + "=" * 70)
    print("КОРАНИЧЕСКАЯ ТАБЛИЦА")
    print("=" * 70)
    q = list(cur.execute("select SURAH, AYAH, WORD, ROOT_WORD from QURAN"))
    qr = collections.Counter(x[3] for x in q)
    print(f"строк: {len(q)}   уникальных ROOT_WORD: {len(qr)}   null: {sum(1 for x in q if not x[3])}")
    print("топ значений (видно, что это не корни):", qr.most_common(6))
    dict_roots = {r[1] for r in rows if r[3] == 1}
    print(f"значений, которых нет среди корней словаря: {len(set(qr) - dict_roots)}")
    print("текста аятов в таблице нет -> для карточки нужен свой корпус Корана")

    print("\n" + "=" * 70)
    print("ПОИСК")
    print("=" * 70)
    for query in ['كتب', 'مكتبة', 'مدرسون', 'البيوت', 'مكتبه', 'كتب ']:
        try:
            n = cur.execute("select count(*) from DICTIONARY where DICTIONARY match ?", (query,)).fetchone()[0]
        except Exception as e:
            n = f"ERR {e}"
        print(f"  FTS match({query!r}) -> {n}")

    print("\n" + "=" * 70)
    print("СОВРЕМЕННАЯ ЛЕКСИКА (ожидается: отсутствует)")
    print("=" * 70)
    for w in ['انترنت', 'حاسوب', 'كمبيوتر', 'تلفزيون', 'هاتف']:
        print(f"  {w:10s} {cur.execute('select count(*) from DICTIONARY_content where c1=?', (w,)).fetchone()[0]}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], resolve='--resolve' in sys.argv)
