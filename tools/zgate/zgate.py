# zenz-gate 学習データ: Sumire N-best (cands) + zenz-v3.2-small の対数尤度で、文ごとに
# 特徴量と「Sumire 1位が正解か / zenz の選択が正解か」を出す。文字列は一切出力しない（数値のみ）。
import sys, math, numpy as np
from llama_cpp import Llama
tsv, cands, model, out = sys.argv[1:5]
K = 10
rows = {}
for line in open(tsv, encoding='utf-8'):
    c = line.rstrip('\n').split('\t')
    if len(c) >= 5 and c[3]:
        rows[c[0]] = (c[1], c[2], c[3], set(c[4].split('|')))
by = {}
for line in open(cands, encoding='utf-8'):
    c = line.rstrip('\n').split('\t')
    try:
        if len(c) < 8 or c[0] not in rows or int(c[1]) >= 50: continue
        by.setdefault(c[0], []).append((int(c[1]), c[2], int(c[3]), int(c[4]), c[7]))
    except ValueError:
        continue
print('sentences', len(rows), 'with cands', len(by), flush=True)
# zenz の前処理名 gpt2-small-japanese-char は本家 llama.cpp に無いので default で上書きし、
# 文字単位モデルなので 1 文字ずつトークン化して連結する。
llm = Llama(model_path=model, n_ctx=512, n_threads=4, logits_all=True, verbose=False,
            kv_overrides={'tokenizer.ggml.pre': 'default'})
_tc = {}
def toks(text):
    out = []
    for ch in text:
        if ch not in _tc:
            _tc[ch] = llm.tokenize(ch.encode(), add_bos=False, special=True)
        out += _tc[ch]
    return out
EOS = llm.token_eos()
def kata(s): return ''.join(chr(ord(ch) + 0x60) if 'ぁ' <= ch <= 'ゖ' else ch for ch in s)
def hira(s): return all('ぁ' <= ch <= 'ゟ' or ch == 'ー' for ch in s)
def segs(sg): return [x.split(':', 1) for x in sg.split('\x1f') if ':' in x]
def bounds(sg):
    b, p = set(), 0
    for r, _ in segs(sg): p += len(r); b.add(p)
    return b
o = open(out, 'w')
o.write('split\tlen\tctx\tgap\tn1448\tn3000\tnseg\tsegdis\tkana2\tkata\tsys_ok\tz_ok\tz_diff\tzmargin\n')
for i, (sid, cl) in enumerate(by.items()):
    split, ctx, reading, gold = rows[sid]
    cl.sort()
    sysc = [x for x in cl if x[2] == 1]
    if not sysc: continue
    top = cl[0]
    gap = (sysc[1][3] - sysc[0][3]) if len(sysc) > 1 else 99999
    n1448 = sum(1 for x in sysc if x[3] - sysc[0][3] < 1448) - 1
    n3000 = sum(1 for x in sysc if x[3] - sysc[0][3] < 3000) - 1
    s1 = segs(sysc[0][4])
    segdis = int(len(sysc) > 1 and bounds(sysc[0][4]) != bounds(sysc[1][4]))
    kana2 = sum(1 for r, w in s1 if len(w) >= 2 and hira(w))
    kat = int(any(all('ァ' <= ch <= 'ヺ' or ch == 'ー' for ch in w) for _, w in s1))
    prompt = ('\uEE02' + ctx[-40:] if ctx else '') + '\uEE00' + kata(reading) + '\uEE01'
    P = toks(prompt)
    llm.reset(); llm.eval(P)
    lls = []
    for x in cl[:K]:
        C = toks(x[1]) + [EOS]
        llm.n_tokens = len(P)
        llm.eval(C)
        lg = np.array(llm.scores[len(P) - 1:len(P) - 1 + len(C)], dtype=np.float64)
        lg = lg - lg.max(1, keepdims=True); lp = lg - np.log(np.exp(lg).sum(1, keepdims=True))
        lls.append(float(sum(lp[j, t] for j, t in enumerate(C))))
    zi = int(np.argmax(lls)); srt = sorted(lls, reverse=True)
    zm = srt[0] - srt[1] if len(srt) > 1 else 99.0
    o.write('\t'.join(map(str, [split, len(reading), len(ctx), gap, n1448, n3000, len(s1), segdis, kana2, kat,
        int(top[1] in gold), int(cl[zi][1] in gold), int(zi != 0), round(zm, 3)])) + '\n')
    if i % 200 == 0: print(i, flush=True)
o.close()
