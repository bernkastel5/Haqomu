#!/usr/bin/env python3
"""Живые предложения (Tatoeba) + ручная разметка смысла: ArabGlossBERT против словарного порядка."""
import json, re, numpy as np
HAR = re.compile(r"[\u064B-\u0652\u0670\u0640]")
def un(s): return HAR.sub("", s or "").strip()

def main():
    import torch
    from transformers import AutoTokenizer, AutoModelForSequenceClassification
    its = json.load(open("data/quality/live_labels.json", encoding="utf-8"))
    tok = AutoTokenizer.from_pretrained("models/agb")
    m = AutoModelForSequenceClassification.from_pretrained("models/agb").eval()
    @torch.no_grad()
    def p(sent, gloss):
        b = tok(un(sent), un(gloss), truncation=True, max_length=192, return_tensors="pt")
        lg = m(**b).logits[0].numpy(); e = np.exp(lg - lg.max()); q = e / e.sum()
        return float(q[1])
    kept = n_keep = fixed = n_fix = ok = base = 0
    for it in its:
        sc = [p(it["sentence"], d) for d in it["defs"]]
        pick = int(max(range(len(sc)), key=lambda j: sc[j]))
        it["pick"] = pick; it["p"] = [round(x, 2) for x in sc]
        ok += (pick == it["gold"]); base += (it["gold"] == 0)
        if it["gold"] == 0:
            n_keep += 1; kept += (pick == 0)
        else:
            n_fix += 1; fixed += (pick == it["gold"])
        print(f"{'OK ' if pick==it['gold'] else '   '} {it['word']:8s} gold={it['gold']+1} pick={pick+1} "
              f"n={len(sc)} p={it['p']}", flush=True)
    N = len(its)
    print(f"\nживые предложения, {N} элементов (ручная разметка)")
    print(f"  «всегда смысл №1» (словарный порядок): {base}/{N} = {100*base/N:.0f}%")
    print(f"  ArabGlossBERT:                          {ok}/{N} = {100*ok/N:.0f}%")
    print(f"  где №0 верен — не сломала:  {kept}/{n_keep} = {100*kept/n_keep:.0f}%")
    print(f"  где №0 неверен — исправила: {fixed}/{n_fix} = {100*fixed/n_fix:.0f}%")
    for br in (0.60, 0.70, 0.80):
        est = br * (kept/n_keep) + (1-br) * (fixed/n_fix)
        print(f"  проекция на реальную базовую частоту {br:.0%}: {100*est:.0f}% (словарный порядок даёт {100*br:.0f}%)")
    json.dump(its, open("data/quality/live_result.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
