#!/usr/bin/env python3
"""Собрать бандл модели разрешения смыслов для приложения Haqomu (фаза 9).

Приложение не вшивает веса (CC BY-SA 4.0, 135–270 МБ) и не раздаёт их: пользователь
готовит бандл у себя и устанавливает файлом. Бандл — обычный zip из трёх файлов:

    model.onnx    граф (классификатор BERT-типа на паре «предложение [SEP] толкование»)
    vocab.txt     словарь WordPiece, один токен на строку
    model.json    наш манифест (см. app/src/main/java/.../gloss/ModelBundle.kt)

Что делает скрипт (stdlib + onnx + onnxruntime + transformers, если они есть):
  1. берёт уже экспортированный `model.onnx` и `vocab.txt` и упаковывает их с манифестом;
  2. либо, при `--from-hf`, скачивает `SinaLab/ArabGlossBERT` с Hugging Face,
     экспортирует в ONNX через `optimum` и только потом упаковывает.

Проверки перед упаковкой (те же, что делает приложение, но заранее):
  • файлы непустые, словарь содержит [PAD] [UNK] [CLS] [SEP];
  • имена входов/выхода графа совпадают с манифестом (или подставляются из графа);
  • размер не выходит за потолок приложения.

Примеры:
    # из готового экспорта (рекомендуется: экспорт делается один раз)
    python3 tools/make_model_bundle.py --onnx agb_int8.onnx --vocab vocab.txt \
        --name "ArabGlossBERT int8" --out haqomu-agb-int8.zip

    # полный путь: скачать с HF и экспортировать (нужны optimum, transformers, torch)
    python3 tools/make_model_bundle.py --from-hf --quantize int8 \
        --out haqomu-agb-int8.zip

Данные и веса в репозиторий не попадают: скрипт пишет только путь, указанный в --out.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile

SPECIALS = ["[PAD]", "[UNK]", "[CLS]", "[SEP]"]
MODEL_FILE, VOCAB_FILE, MANIFEST_FILE = "model.onnx", "vocab.txt", "model.json"
MAX_BYTES = 400 * 1024 * 1024  # потолок приложения (ModelBundle.MAX_UNCOMPRESSED_BYTES)
HF_REPO = "SinaLab/ArabGlossBERT"


def die(message: str) -> "NoReturn":  # noqa: F821
    print(f"ошибка: {message}", file=sys.stderr)
    raise SystemExit(1)


def check_vocab(path: str) -> None:
    with open(path, encoding="utf-8") as f:
        tokens = {line.rstrip("\n") for line in f}
    missing = [t for t in SPECIALS if t not in tokens]
    if missing:
        die(f"в словаре нет {missing}")
    if len(tokens) < 100:
        die("словарь подозрительно короткий")


def graph_io(onnx_path: str) -> tuple[list[str], list[str]]:
    """Имена входов и выходов графа. Без onnx — пустые списки (манифест по умолчанию)."""
    try:
        import onnx  # type: ignore
    except Exception:
        print("onnx не установлен: имена входов/выхода берутся из умолчаний манифеста",
              file=sys.stderr)
        return [], []
    model = onnx.load(onnx_path, load_external_data=False)
    inputs = [i.name for i in model.graph.input]
    outputs = [o.name for o in model.graph.output]
    return inputs, outputs


def export_from_hf(out_onnx: str, out_vocab: str, quantize: str) -> None:
    try:
        from transformers import AutoTokenizer  # type: ignore
        from optimum.onnxruntime import ORTModelForSequenceClassification  # type: ignore
    except Exception as e:  # pragma: no cover - зависит от машины
        die(f"для --from-hf нужны transformers и optimum: {e}")

    print(f"скачиваю {HF_REPO} с Hugging Face…", file=sys.stderr)
    model = ORTModelForSequenceClassification.from_pretrained(HF_REPO, export=True)
    tok = AutoTokenizer.from_pretrained(HF_REPO)
    model.save_pretrained(os.path.dirname(os.path.abspath(out_onnx)) or ".")
    tok.save_pretrained(os.path.dirname(os.path.abspath(out_vocab)) or ".")

    exported = os.path.join(os.path.dirname(os.path.abspath(out_onnx)) or ".", "model.onnx")
    if not os.path.exists(exported):
        die("экспорт не дал model.onnx")
    if os.path.abspath(exported) != os.path.abspath(out_onnx):
        os.replace(exported, out_onnx)
    vocab_src = os.path.join(os.path.dirname(os.path.abspath(out_vocab)) or ".", "vocab.txt")
    if os.path.exists(vocab_src) and os.path.abspath(vocab_src) != os.path.abspath(out_vocab):
        os.replace(vocab_src, out_vocab)

    if quantize in ("int8", "fp16"):
        try:
            from onnxruntime.quantization import QuantType, quantize_dynamic  # type: ignore
        except Exception as e:  # pragma: no cover
            die(f"для --quantize нужен onnxruntime: {e}")
        quantized = out_onnx.replace(".onnx", f".{quantize}.onnx")
        if quantize == "int8":
            quantize_dynamic(out_onnx, quantized, weight_type=QuantType.QInt8)
        else:
            from onnxconverter_common import float16  # type: ignore
            import onnx  # type: ignore
            m = onnx.load(out_onnx)
            onnx.save(float16.convert_float_to_float16(m), quantized)
        os.replace(quantized, out_onnx)


def main() -> None:
    ap = argparse.ArgumentParser(description="Бандл модели разрешения смыслов для Haqomu")
    ap.add_argument("--onnx", help="готовый model.onnx")
    ap.add_argument("--vocab", help="готовый vocab.txt")
    ap.add_argument("--from-hf", action="store_true",
                    help=f"скачать {HF_REPO} и экспортировать (нужны optimum/transformers)")
    ap.add_argument("--quantize", choices=["none", "int8", "fp16"], default="none")
    ap.add_argument("--workdir", default="data/model", help="куда класть экспорт при --from-hf")
    ap.add_argument("--name", default="ArabGlossBERT", help="имя в манифесте")
    ap.add_argument("--threshold", type=float, default=0.3, help="порог разрыва вероятностей")
    ap.add_argument("--max-length", type=int, default=192)
    ap.add_argument("--out", required=True, help="куда положить zip-бандл")
    args = ap.parse_args()

    onnx_path, vocab_path = args.onnx, args.vocab
    if args.from_hf:
        os.makedirs(args.workdir, exist_ok=True)
        onnx_path = os.path.join(args.workdir, MODEL_FILE)
        vocab_path = os.path.join(args.workdir, VOCAB_FILE)
        export_from_hf(onnx_path, vocab_path, args.quantize)
    if not onnx_path or not vocab_path:
        die("нужен --onnx и --vocab (или --from-hf)")

    for path in (onnx_path, vocab_path):
        if not os.path.isfile(path) or os.path.getsize(path) == 0:
            die(f"нет файла {path}")
    check_vocab(vocab_path)

    inputs, outputs = graph_io(onnx_path)
    input_ids = "input_ids" if not inputs or "input_ids" in inputs else \
        next((i for i in inputs if i not in ("attention_mask", "token_type_ids")), "input_ids")
    attention = "attention_mask" if not inputs or "attention_mask" in inputs else "attention_mask"
    types = "token_type_ids" if "token_type_ids" in inputs else "token_type_ids"
    logits = "logits" if not outputs or "logits" in outputs else (outputs[0] if outputs else "logits")

    total = os.path.getsize(onnx_path) + os.path.getsize(vocab_path)
    if total > MAX_BYTES:
        die(f"бандл слишком большой: {total / 1048576:.0f} МБ")

    manifest = {
        "model": args.name,
        "version": 1,
        "license": "CC BY-SA 4.0",
        "source": HF_REPO,
        "size_bytes": total,
        "max_length": args.max_length,
        "lowercase": False,
        "strip_accents": True,
        "input_ids": input_ids,
        "attention_mask": attention,
        "token_type_ids": types,
        "logits": logits,
        "labels": 2,
        "threshold": args.threshold,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.write(onnx_path, MODEL_FILE)
        z.write(vocab_path, VOCAB_FILE)
        z.writestr(MANIFEST_FILE, json.dumps(manifest, ensure_ascii=False, indent=2))
    size = os.path.getsize(args.out)
    print(f"готово: {args.out} ({size / 1048576:.1f} МБ), модель {total / 1048576:.1f} МБ")
    print(f"в приложении: «Модель смыслов» → «Выбрать файл…» → {os.path.basename(args.out)}")


if __name__ == "__main__":
    main()
