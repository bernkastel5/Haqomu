#!/usr/bin/env python3
"""
Сборка набора Haqomu для Anki (`haqomu.apkg`).

Что внутри артефакта (D-009):
  * колода «Haqomu» (фиксированный id);
  * note type «Haqomu Arabic» (фиксированный id) — адаптация JP Mining Note:
    фуригана заменена ташкилем; поля:
    Expression, Tashkeel, Meaning, Sentence, Root, Dictionary, Notes;
  * одна вводная карточка (что это за колода и как ею пользоваться).

Файл — производная работа самого проекта (словарных данных нет), поэтому
распространяется свободно: в GitHub Releases (фаза 10) и внутри APK как raw-ресурс,
чтобы пользователь мог «Поделиться» файлом прямо из приложения.

Генератор самодостаточен (только stdlib) и воспроизводит формат legacy .apkg
(collection.anki2, схема Anki 2.1, импортируется и AnkiDroid, и Anki Desktop):
структура таблиц и JSON повторяют эталонную реализацию пакета `genanki`,
поэтому внешних зависимостей не требует.

Запуск:
    python3 tools/build_haqomu_apkg.py --out haqomu.apkg
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import tempfile
import time
import zipfile

# Фиксированные идентификаторы: стабильность важна, чтобы повторный импорт набора
# обновлял (а не дублировал) колоду и note type в коллекции пользователя.
MODEL_ID = 1763078400000
DECK_ID = 1763078400001
MODEL_NAME = "Haqomu Arabic"
DECK_NAME = "Haqomu"

FIELDS = [
    "Expression", "Tashkeel", "Meaning", "Sentence",
    "Root", "Dictionary", "Notes",
]

# rtl: поля с арабским содержимым открываются в RTL-режиме при редактировании.
FIELD_RTL = {
    "Expression": True, "Tashkeel": True, "Meaning": True,
    "Sentence": True, "Root": True, "Dictionary": False, "Notes": False,
}

QFMT = (
    '<div class="front">'
    '<div class="word">{{Tashkeel}}</div>'
    '{{#Root}}<div class="root">{{Root}}</div>{{/Root}}'
    '</div>'
)
AFMT = (
    '{{FrontSide}}'
    '<hr id="answer">'
    '<div class="meaning">{{Meaning}}</div>'
    '{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}'
    '<div class="dict">{{Dictionary}}</div>'
    '{{#Notes}}<div class="notes">{{Notes}}</div>{{/Notes}}'
)

# Плоский стиль, без украшательств; огласовка не клипается (большой line-height).
CSS = (
    ".card {\n"
    "  direction: rtl;\n"
    "  font-family: 'Amiri', 'Scheherazade New', 'Noto Naskh Arabic', serif;\n"
    "  font-size: 20px;\n"
    "  line-height: 1.9;\n"
    "  color: #1f1b16;\n"
    "  background: #fdfcf9;\n"
    "  text-align: center;\n"
    "  padding: 16px;\n"
    "}\n"
    ".word {\n"
    "  font-size: 40px;\n"
    "  line-height: 2.1;\n"
    "}\n"
    ".root {\n"
    "  color: #6b655c;\n"
    "  font-size: 18px;\n"
    "  margin-top: 4px;\n"
    "}\n"
    "hr#answer {\n"
    "  border: none;\n"
    "  border-top: 1px solid #d8d4cc;\n"
    "  margin: 14px 0;\n"
    "}\n"
    ".meaning {\n"
    "  text-align: right;\n"
    "  line-height: 1.9;\n"
    "}\n"
    ".sentence {\n"
    "  text-align: right;\n"
    "  color: #3c3831;\n"
    "  margin-top: 12px;\n"
    "}\n"
    ".dict {\n"
    "  color: #6b655c;\n"
    "  font-size: 15px;\n"
    "  text-align: center;\n"
    "  margin-top: 14px;\n"
    "}\n"
    ".notes {\n"
    "  color: #6b655c;\n"
    "  font-size: 15px;\n"
    "  text-align: right;\n"
    "  margin-top: 10px;\n"
    "}\n"
    ".card.nightMode {\n"
    "  color: #e6e2d8;\n"
    "  background: #1f1b16;\n"
    "}\n"
    ".card.nightMode .root, .card.nightMode .dict, .card.nightMode .notes { color: #a9a499; }\n"
    ".card.nightMode .sentence { color: #cfccc0; }\n"
    ".card.nightMode hr#answer { border-top-color: #45413a; }\n"
)

LATEX_PRE = (
    '\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n'
    '\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n'
    '\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n'
)
LATEX_POST = '\\end{document}'

INTRO_FIELDS = [
    "Haqomu",
    "Haqomu",
    ("هذه بطاقة ترحيبية من Haqomu: الوجه يعرض الكلمة المشكولة مع جذرها، "
     "والظهر — المعنى والجملة التوضيحية إن وُجدت. "
     "This is the intro card of the Haqomu deck: the front shows the voweled word and its root, "
     "the back shows the meaning and an example sentence when present."),
    "",
    "—",
    "Haqomu",
    ("Можете удалить эту карточку. / You can delete this card. "
     "Чтобы сменить колоду для новых карточек, откройте Настройка Anki в приложении. "
     "To change the target deck for new cards, open Anki setup in the app."),
]

APKG_SCHEMA = """
CREATE TABLE col (
    id              integer primary key,
    crt             integer not null,
    mod             integer not null,
    scm             integer not null,
    ver             integer not null,
    dty             integer not null,
    usn             integer not null,
    ls              integer not null,
    conf            text not null,
    models          text not null,
    decks           text not null,
    dconf           text not null,
    tags            text not null
);
CREATE TABLE notes (
    id              integer primary key,
    guid            text not null,
    mid             integer not null,
    mod             integer not null,
    usn             integer not null,
    tags            text not null,
    flds            text not null,
    sfld            integer not null,
    csum            integer not null,
    flags           integer not null,
    data            text not null
);
CREATE TABLE cards (
    id              integer primary key,
    nid             integer not null,
    did             integer not null,
    ord             integer not null,
    mod             integer not null,
    usn             integer not null,
    type            integer not null,
    queue           integer not null,
    due             integer not null,
    ivl             integer not null,
    factor          integer not null,
    reps            integer not null,
    lapses          integer not null,
    left            integer not null,
    odue            integer not null,
    odid            integer not null,
    flags           integer not null,
    data            text not null
);
CREATE TABLE revlog (
    id              integer primary key,
    cid             integer not null,
    usn             integer not null,
    ease            integer not null,
    ivl             integer not null,
    lastIvl         integer not null,
    factor          integer not null,
    time            integer not null,
    type            integer not null
);
CREATE TABLE graves (
    usn             integer not null,
    oid             integer not null,
    type            integer not null
);
CREATE INDEX ix_notes_usn on notes (usn);
CREATE INDEX ix_cards_usn on cards (usn);
CREATE INDEX ix_revlog_usn on revlog (usn);
CREATE INDEX ix_cards_nid on cards (nid);
CREATE INDEX ix_cards_sched on cards (did, queue, due);
CREATE INDEX ix_revlog_cid on revlog (cid);
CREATE INDEX ix_notes_csum on notes (csum);
"""

# Алфавит base91, как в Anki (порядок важен для однозначности guid).
BASE91_TABLE = list(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789"
    "!#$%&()*+,-./:;<=>?@[]^_`{|}~"
)


def guid_for(*values: str) -> str:
    digest = hashlib.sha256("__".join(values).encode("utf-8")).digest()[:8]
    number = int.from_bytes(digest, "big")
    out: list[str] = []
    while number > 0:
        number, idx = divmod(number, len(BASE91_TABLE))
        out.append(BASE91_TABLE[idx])
    return "".join(reversed(out))


def csum_for(sort_field: str) -> int:
    return int(hashlib.sha1(sort_field.encode("utf-8")).hexdigest()[:8], 16)


def model_json(timestamp: float) -> dict:
    fields = []
    for index, name in enumerate(FIELDS):
        fields.append({
            "name": name,
            "ord": index,
            "sticky": False,
            "rtl": bool(FIELD_RTL[name]),
            "font": "Amiri" if FIELD_RTL[name] else "Liberation Sans",
            "size": 20,
            "media": [],
            "collapsed": False,
            "description": "",
        })
    templates = [{
        "name": "Card 1",
        "ord": 0,
        "qfmt": QFMT,
        "afmt": AFMT,
        "bqfmt": "",
        "bafmt": "",
        "bfont": "",
        "bsize": 0,
        "did": None,
    }]
    return {
        "id": str(MODEL_ID),
        "name": MODEL_NAME,
        "type": 0,
        "mod": int(timestamp),
        "usn": -1,
        "sortf": 0,
        "did": DECK_ID,
        "tmpls": templates,
        "flds": fields,
        "css": CSS,
        "latexPre": LATEX_PRE,
        "latexPost": LATEX_POST,
        "latexsvg": False,
        "req": [[0, "all", [1]]],  # Tashkeel обязателен для лицевой стороны
        "tags": [],
        "vers": [],
    }


def deck_json() -> dict:
    return {
        "collapsed": False,
        "conf": 1,
        "desc": "",
        "dyn": 0,
        "extendNew": 10,
        "extendRev": 50,
        "id": DECK_ID,
        "lrnToday": [0, 0],
        "mod": 1763078400,
        "name": DECK_NAME,
        "newToday": [0, 0],
        "revToday": [0, 0],
        "timeToday": [0, 0],
        "usn": -1,
    }


def write_apkg(path: str) -> None:
    timestamp = time.time()
    dbfile, dbpath = tempfile.mkstemp(suffix=".anki2")
    os.close(dbfile)
    try:
        conn = sqlite3.connect(dbpath)
        cur = conn.cursor()
        cur.executescript(APKG_SCHEMA)
        cur.executescript(
            """
            INSERT INTO col VALUES(
                1, 1763078400, 1763078400000, 1763078400000, 11, 0, 0, 0,
                '{"activeDecks":[1763078400001],"addToCur":true,"collapseTime":1200,
                  "curDeck":1763078400001,"curModel":"1763078400000","dueCounts":true,
                  "estTimes":true,"newBury":true,"newSpread":0,"nextPos":1,
                  "sortBackwards":false,"sortType":"noteFld","timeLim":0}',
                '{}',
                '{}',
                '{"1": {"autoplay":true,"id":1,"lapse":{"delays":[10],"leechAction":0,"leechFails":8,"minInt":1,"mult":0},
                  "maxTaken":60,"mod":0,"name":"Default",
                  "new":{"bury":true,"delays":[1,10],"initialFactor":2500,"ints":[1,4,7],"order":1,"perDay":20,"separate":true},
                  "replayq":true,
                  "rev":{"bury":true,"ease4":1.3,"fuzz":0.05,"ivlFct":1,"maxIvl":36500,"minSpace":1,"perDay":100},
                  "timer":0,"usn":0}}',
                '{}'
            );
            """
        )
        # Заполняем models/decks в col валидным JSON
        cur.execute("UPDATE col SET decks = ?",
                    (json.dumps({str(DECK_ID): deck_json()}),))
        cur.execute("UPDATE col SET models = ?",
                    (json.dumps({str(MODEL_ID): model_json(timestamp)}),))

        note_id = MODEL_ID + 1
        cur.execute(
            "INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (
                note_id,
                guid_for(*INTRO_FIELDS),
                MODEL_ID,
                int(timestamp),
                -1,
                " ",
                "\x1f".join(INTRO_FIELDS),
                INTRO_FIELDS[0],
                csum_for(INTRO_FIELDS[0]),
                0,
                "",
            ),
        )
        cur.execute(
            "INSERT INTO cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                note_id + 1,
                note_id,
                DECK_ID,
                0,
                int(timestamp),
                -1,
                0,
                0,
                1,   # due: позиция среди новых карточек
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
            ),
        )
        conn.commit()
        conn.close()
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as outzip:
            outzip.write(dbpath, "collection.anki2")
            outzip.writestr("media", "{}")
    finally:
        if os.path.exists(dbpath):
            os.remove(dbpath)


def main() -> None:
    parser = argparse.ArgumentParser(description="Собрать haqomu.apkg (колода Haqomu + note type + вводная карточка)")
    parser.add_argument("--out", default="haqomu.apkg", help="куда положить результат")
    args = parser.parse_args()
    write_apkg(args.out)
    print(f"записано: {args.out}")


if __name__ == "__main__":
    main()
