#!/usr/bin/env python3
"""Конвертер морфологической базы CAMeL (calima-msa-r13) в компактный пак для Haqomu.

Важно: `morphology.db` из CAMeL — это НЕ SQLite, а текстовый дамп со секциями
(###PREFIXES###, ###SUFFIXES###, ###STEMS###, ###TABLE AB### ...). CAMeL разбирает
его в память при старте. Этот конвертер делает то же самое один раз и сохраняет
только то, что нужно приложению для разбора (анализ → леммы + pos):

  - stems:     голый стебель (без огласовки) -> pos, lex (лемма), lex_logprob, root, cat
  - prefixes / suffixes: аффиксы со всеми feats (JSON) — их мало
  - compat:    таблицы совместимости категорий (AB: prefix->stem, BC: stem->suffix,
               AC: prefix->suffix)
  - meta:      schema_version, source, license (GPL-2), build_date, счётчики

Итоговый файл читается на Android нативным SQLite (android.database.sqlite),
никакого собственного бинарного формата не нужно.

Исходник GPL-2, приложение GPL-3.0 (см. docs/adr/0003). Данные calima в git не
кладём никогда: пользователь скачивает их сам (`camel_data -i morphology-db-msa-r13`
или zip с релиза CAMeL-Lab) и конвертирует у себя.

Использование:
  python3 tools/build_camel_pack.py --src path/to/morphology.db --out camel_pack.sqlite
  python3 tools/build_camel_pack.py --selftest     # синтетическая база, проверка конвертера
"""
import argparse, json, sqlite3, sys, os, datetime

SCHEMA_VERSION = 1
SECTION_PREFIXES = '###PREFIXES###'
SECTION_SUFFIXES = '###SUFFIXES###'
SECTION_STEMS = '###STEMS###'
SECTION_AB = '###TABLE AB###'
SECTION_BC = '###TABLE BC###'
SECTION_AC = '###TABLE AC###'
SECTIONS = [SECTION_PREFIXES, SECTION_SUFFIXES, SECTION_STEMS,
            SECTION_AB, SECTION_BC, SECTION_AC]


def parse_feats(toks):
    out = {}
    for tok in toks:
        if not tok:
            continue
        kv = tok.split(':', 1)
        if len(kv) < 2:
            continue
        out[kv[0]] = kv[1]
    return out


def strip_lex(lex):
    """Точная семантика camel_tools.morphology.utils.strip_lex:
    отрезать суффикс после первого '_' или '-' (lex-идентификаторы вида 'ذهب_1')."""
    import re
    return re.split(r'[_-]', (lex or '').strip(), maxsplit=1)[0].strip()


def convert(src, out, keep_feats=False):
    stems, prefixes, suffixes = [], [], []
    compat = []
    counts = {'stems': 0, 'prefixes': 0, 'suffixes': 0, 'compat_ab': 0,
              'compat_bc': 0, 'compat_ac': 0}
    section = None
    with open(src, 'r', encoding='utf-8') as f:
        for raw in f:
            line = raw.rstrip('\n')
            s = line.strip()
            if s in SECTIONS:
                section = s
                continue
            if section == SECTION_PREFIXES and line:
                parts = line.split('\t')
                if len(parts) != 3:
                    continue
                feats = parse_feats(parts[2].split())
                prefixes.append((parts[0].strip(), parts[1], json.dumps(feats, ensure_ascii=False)))
            elif section == SECTION_SUFFIXES and line:
                parts = line.split('\t')
                if len(parts) != 3:
                    continue
                feats = parse_feats(parts[2].split())
                suffixes.append((parts[0].strip(), parts[1], json.dumps(feats, ensure_ascii=False)))
            elif section == SECTION_STEMS and line:
                parts = line.split('\t')
                if len(parts) != 3:
                    continue
                feats = parse_feats(parts[2].split(' ') if ' ' in parts[2] else parts[2].split())
                try:
                    logprob = float(feats.get('lex_logprob', '-99'))
                except ValueError:
                    logprob = -99.0
                stem = parts[0]
                cat = parts[1]
                pos = feats.get('pos', '')
                lex = strip_lex(feats.get('lex', ''))
                root = feats.get('root', '')
                if keep_feats:
                    feats_json = json.dumps(feats, ensure_ascii=False)
                else:
                    feats_json = None
                # stem_diac — огласованный вид стебля из calima (Ф15: огласовка в карточке).
                stem_diac = feats.get('diac', '')
                stems.append((stem, cat, pos, lex, logprob, root, feats_json, stem_diac))
            elif section == SECTION_AB and s:
                toks = s.split()
                if len(toks) == 2:
                    compat.append(('ab', toks[0], toks[1]))
            elif section == SECTION_BC and s:
                toks = s.split()
                if len(toks) == 2:
                    compat.append(('bc', toks[0], toks[1]))
            elif section == SECTION_AC and s:
                toks = s.split()
                if len(toks) == 2:
                    compat.append(('ac', toks[0], toks[1]))

    counts['stems'] = len(stems)
    counts['prefixes'] = len(prefixes)
    counts['suffixes'] = len(suffixes)
    counts['max_prefix_size'] = max((len(p) for p, _, _ in prefixes), default=0)
    counts['max_suffix_size'] = max((len(s) for s, _, _ in suffixes), default=0)
    counts['compat_ab'] = sum(1 for k, _, _ in compat if k == 'ab')
    counts['compat_bc'] = sum(1 for k, _, _ in compat if k == 'bc')
    counts['compat_ac'] = sum(1 for k, _, _ in compat if k == 'ac')

    if os.path.exists(out):
        os.remove(out)
    conn = sqlite3.connect(out)
    cur = conn.cursor()
    cur.executescript("""
    CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    CREATE TABLE stems (
      stem TEXT NOT NULL, cat TEXT NOT NULL, pos TEXT NOT NULL,
      lex TEXT NOT NULL, lex_logprob REAL NOT NULL, root TEXT, feats TEXT,
      stem_diac TEXT NOT NULL DEFAULT ''
    );
    CREATE TABLE affixes (
      form TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('prefix','suffix')),
      cat TEXT NOT NULL, feats TEXT NOT NULL
    );
    CREATE TABLE compat (kind TEXT NOT NULL CHECK(kind IN ('ab','bc','ac')),
                         a TEXT NOT NULL, b TEXT NOT NULL);
    """)
    cur.executemany('INSERT INTO stems VALUES (?,?,?,?,?,?,?,?)', stems)
    cur.executemany('INSERT INTO affixes VALUES (?,?,?,?)',
                    [(p, 'prefix', c, j) for p, c, j in prefixes] +
                    [(p, 'suffix', c, j) for p, c, j in suffixes])
    cur.executemany('INSERT INTO compat VALUES (?,?,?)', compat)
    cur.executescript("""
    CREATE INDEX idx_stems_stem ON stems(stem);
    CREATE INDEX idx_affixes_form ON affixes(form, kind);
    CREATE INDEX idx_compat_ab ON compat(kind, a);
    """)
    meta = {'schema_version': str(SCHEMA_VERSION), 'source': 'calima-msa-r13',
            'license': 'GPL-2', 'build_date': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d'),
            **{k: str(v) for k, v in counts.items()}}
    cur.executemany('INSERT INTO meta VALUES (?,?)', meta.items())
    conn.commit()
    cur.execute('VACUUM')
    conn.close()
    return counts


def selftest():
    """Синтетическая calima-база -> конвертер -> запрос лемм, как в приложении."""
    sample = "\n".join([
        '###DEFINES###',
        'DEFINE pos VERB:VRB NOUN:NOM',
        '###DEFAULTS###',
        "DEFAULT pos:NOUN",
        '###ORDER###',
        'ORDER pos lex',
        '###TOKENIZATIONS###',
        '###STEMBACKOFF###',
        '###PREFIXES###',
        'و\tCONJ\tpos:CC',
        'ال\tDET\tpos:DET',
        '###SUFFIXES###',
        '\tNSUFF\tpos:- lex:-',
        'ون\tM_PL_NOM\tpos:- lex:-',
        '###STEMS###',
        'ذهب\tVERB_STEM\tpos:VERB lex:ذهب lex_logprob:-0.50 root:ذ ه ب diac:ذَهَبَ',
        'بيت\tNOUN_STEM\tpos:NOUN lex:بيت lex_logprob:-0.80 root:ب ي ت',
        '###TABLE AB###',
        'CONJ VERB_STEM',
        'CONJ NOUN_STEM',
        'DET NOUN_STEM',
        '###TABLE BC###',
        'VERB_STEM NSUFF',
        'NOUN_STEM M_PL_NOM',
        'NOUN_STEM NSUFF',
        '###TABLE AC###',
        'CONJ NSUFF',
        'CONJ M_PL_NOM',
        'DET M_PL_NOM',
        'DET NSUFF',
    ])
    import tempfile
    with tempfile.NamedTemporaryFile('w', suffix='.db', delete=False, encoding='utf-8') as f:
        f.write(sample)
        src = f.name
    out = src.replace('.db', '.sqlite')
    counts = convert(src, out)
    conn = sqlite3.connect(out)
    cur = conn.cursor()
    n = cur.execute('SELECT count(*) FROM stems').fetchone()[0]
    assert n == 2, n
    row = cur.execute("SELECT pos, lex FROM stems WHERE stem='ذهب'").fetchone()
    assert row == ('VERB', 'ذهب'), row
    # эмуляция разбора как в Kotlin: сегментация + compat из (prefix,stem,suffix)
    def segment(w):
        for p in range(0, min(2, len(w) - 1) + 1):
            for s in range(max(1, len(w) - p - 1), len(w) - p + 1):
                yield w[:p], w[p:p + s], w[p + s:]
    got = []
    for pre, stem, suf in segment('وذهب'):
        pc = {r[0] for r in cur.execute("SELECT cat FROM affixes WHERE form=? AND kind='prefix'", (pre,))}
        sc = {r[0] for r in cur.execute('SELECT cat FROM stems WHERE stem=?', (stem,))}
        fc = {r[0] for r in cur.execute("SELECT cat FROM affixes WHERE form=? AND kind='suffix'", (suf,))}
        ok_ab = cur.execute("SELECT 1 FROM compat WHERE kind='ab' AND a IN (%s) AND b IN (%s) LIMIT 1" % (
            ','.join('?' * len(pc)) or 'NULL', ','.join('?' * len(sc)) or 'NULL'), (*pc, *sc)) if pc and sc else None
        if ok_ab and ok_ab.fetchone():
            got.append((pre, stem, suf))
    assert ('و', 'ذهب', '') in got, got
    # Ф15: огласованный вид стебля лежит отдельной колонкой и доступен запросом
    diac = cur.execute("SELECT stem_diac FROM stems WHERE stem='ذهب'").fetchone()[0]
    assert diac == 'ذَهَبَ', diac
    conn.close()
    os.unlink(src); os.unlink(out)
    print('SELFTEST OK —', counts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', help='путь к calima morphology.db (текстовый)')
    ap.add_argument('--out', help='путь к camel_pack.sqlite')
    ap.add_argument('--keep-feats', action='store_true', help='сохранить все feats стеблей (больше файл)')
    ap.add_argument('--selftest', action='store_true', help='синтетическая проверка конвертера')
    args = ap.parse_args()
    if args.selftest:
        selftest()
        return
    if not args.src or not args.out:
        ap.error('нужны --src и --out (или --selftest)')
    counts = convert(args.src, args.out, keep_feats=args.keep_feats)
    size = os.path.getsize(args.out)
    print('stems:', counts['stems'], '| prefixes:', counts['prefixes'], '| suffixes:', counts['suffixes'])
    print('compat ab/bc/ac:', counts['compat_ab'], counts['compat_bc'], counts['compat_ac'])
    print('camel_pack: %.1f МБ -> %s' % (size / 1e6, args.out))


if __name__ == '__main__':
    main()
