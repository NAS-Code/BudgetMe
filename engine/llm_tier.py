"""
LLM tier for merchants the engine has never seen.

The prompt is built from categories.json at runtime, so adding or editing a
category in the app changes what the model sees. No code changes needed.

Usage
  pip install anthropic pandas
  export ANTHROPIC_API_KEY=sk-ant-...
  python llm_tier.py --show-prompt            # print the system prompt, no API calls
  python llm_tier.py --backtest               # run on Apr-Sep 2026 unknown merchants
  python llm_tier.py --backtest --model claude-sonnet-5-5

Reads config/categories.json and data/labeled_transactions.csv from the project root. Results are cached in llm_cache.jsonl so re-runs
don't re-bill; delete it after changing the prompt.
"""
import argparse, json, os, re, datetime as dt
from collections import Counter, defaultdict
import pandas as pd
import predictor as P

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_MODEL = "claude-haiku-4-5-20251001"   # fast and cheap: one call per new merchant
CACHE = os.path.join(HERE, "llm_cache.jsonl")

# ---------------------------------------------------------------- prompt
SYSTEM_TEMPLATE = """You categorize one personal purchase at a time for a budgeting app. The user will see your top choices as buttons on a phone notification and tap one, so a good ranking matters more than a single answer.

The categories are the user's own, defined in their words below. Follow their definitions, not general conventions. For example, if they file restaurants under "Fun", do that, even though many budgets would call it "Food".

<categories>
{categories}
</categories>

<how_this_user_files_things>
Merchants this user has filed most often in each category. Use them to calibrate what each category means to this person.
{examples}
</how_this_user_files_things>

How to decide:
1. First work out what kind of business the merchant is. Bank descriptors are often truncated or prefixed by payment processors (SQ *, TST*, SP *, PAYPAL *, etc.). Strip those and use your knowledge of real businesses.
2. Then apply the category definitions, including any timing or amount rules in them (weekday vs. weekend, dollar thresholds).
3. {fallback_rule}
4. If the purchase is a person-to-person payment (Venmo, Zelle, Cash App) and a note is given, the note describes what was bought; categorize by the note.

Respond with only a JSON object, no other text:
{{"merchant_type": "<a few words: what this business is>",
 "ranking": [{{"category": "<exact category name>", "probability": <0-1>}}, ...],
 "reason": "<one short sentence>"}}
Rank every category listed above that is eligible. Probabilities should sum to about 1."""

def load_categories(path=os.path.join(ROOT, "config", "categories.json"), only_ids=None):
    cats = json.load(open(path))["categories"]
    return [c for c in cats if not c.get("archived") and (only_ids is None or c["id"] in only_ids)]

def build_system_prompt(categories, history_df, per_cat=10):
    predictable = [c for c in categories if c.get("predict") != "manual_only"]
    lines = []
    for c in predictable:
        extra = " (fallback: only when nothing else fits)" if c.get("predict") == "fallback_only" else ""
        lines.append(f'- {c["name"]}{extra}: {c["description"]}')
    names = {c["name"] for c in predictable}
    h = history_df[history_df.category.isin(names)].copy()
    h["key"] = h.merchant.map(P.merchant_key)
    stats = h.groupby("key").agg(n=("category", "size"), top=("category", lambda s: s.value_counts().index[0]),
                                 share=("category", lambda s: s.value_counts(normalize=True).iloc[0]),
                                 name=("merchant", lambda s: s.value_counts().index[0]))
    ex = []
    for c in predictable:   # distinctive examples: merchants the user files here almost every time
        top = stats[(stats.top == c["name"]) & (stats.share >= 0.8) & (stats.n >= 3)].sort_values("n", ascending=False)
        if len(top):
            ex.append(f'- {c["name"]}: ' + ", ".join(top.name.head(per_cat)))
    split = stats[(stats.share < 0.8) & (stats.n >= 8)].sort_values("n", ascending=False).name.head(8)
    if len(split):
        ex.append("- Split across categories depending on the occasion: " + ", ".join(split))
    fallback = [c["name"] for c in predictable if c.get("predict") == "fallback_only"]
    fb_rule = (f'"{fallback[0]}" is the user\'s bucket for things that don\'t fit elsewhere, such as subscriptions, services, '
               f'appointments, and one-off purchases. Rank it first only when no other category plausibly fits, '
               f'but always include it in the ranking.') if fallback else "Always rank every eligible category."
    return SYSTEM_TEMPLATE.format(categories="\n".join(lines), examples="\n".join(ex), fallback_rule=fb_rule)

def build_user_message(merchant, amount, date, account=None, note=None):
    parts = [f"Merchant: {merchant}",
             f"Amount: ${abs(amount):.2f}" + (" (refund)" if amount < 0 else ""),
             f"Date: {date.isoformat()} ({date.strftime('%A')})"]
    if account: parts.append(f"Account: {account}")
    if note: parts.append(f"Payment note: {note}")
    return "\n".join(parts)

# ---------------------------------------------------------------- call
def classify(client, system_prompt, user_msg, model=DEFAULT_MODEL):
    resp = client.messages.create(
        model=model, max_tokens=300,
        system=[{"type": "text", "text": system_prompt, "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": user_msg}],
    )
    text = "".join(b.text for b in resp.content if b.type == "text")
    m = re.search(r"\{.*\}", text, re.S)
    return json.loads(m.group(0)) if m else {"ranking": [], "error": text}

def llm_buttons(result, prior, eligible, n=3, llm_weight=0.85):
    """Blend LLM probabilities with the day/amount prior, return top-n eligible categories."""
    p = {r["category"]: float(r.get("probability", 0)) for r in result.get("ranking", []) if r.get("category") in eligible}
    scores = {c: llm_weight * p.get(c, 0) + (1 - llm_weight) * prior.get(c, 0) for c in eligible}
    ranked = sorted(scores, key=lambda c: -scores[c])
    conf = scores[ranked[0]] / (sum(scores.values()) or 1)
    return ranked[:n], round(conf, 2)

# ---------------------------------------------------------------- backtest
def eff_date(r):
    y, m = map(int, r.month.split("-"))
    if isinstance(r.date, dt.date) and r.date.strftime("%Y-%m") == r.month:
        return r.date
    return dt.date(y, m, 15)

def backtest(model):
    import anthropic
    client = anthropic.Anthropic()
    df = pd.read_csv(os.path.join(ROOT, "data", "labeled_transactions.csv"), parse_dates=["date"])
    df["date"] = df.date.dt.date.where(df.date.notna(), None)
    df["flags"] = df["flags"].fillna("")
    df["note"] = df["note"].where(df["note"].notna(), None)
    v = df[(df.kind == "variable") & (~df["flags"].str.contains("placeholder"))].copy()
    v["d"] = v.apply(eff_date, axis=1)
    v = v.sort_values(["d", "source"])
    train, test = v[v.month < "2026-04"], v[(v.month >= "2026-04") & (v.month <= "2026-09")]

    cats = load_categories(only_ids={"fun", "grocery_food", "transportation", "miscellaneous", "work_expenses"})
    system_prompt = build_system_prompt(cats, train)
    eligible = [c["name"] for c in cats if c.get("predict") != "manual_only"]

    cache = {}
    if os.path.exists(CACHE):
        for line in open(CACHE):
            rec = json.loads(line); cache[(rec["model"], rec["msg"])] = rec["result"]

    eng = P.Predictor()
    for r in train.itertuples(): eng.learn(r.merchant, r.amount, r.d, r.category)

    rows = []
    for r in test.itertuples():
        base = eng.predict(r.merchant, r.amount, r.d)
        if "history" not in base["tier"]:
            msg = build_user_message(r.merchant, r.amount, r.d, r.account, r.note)
            if (model, msg) not in cache:
                cache[(model, msg)] = classify(client, system_prompt, msg, model)
                with open(CACHE, "a") as f:
                    f.write(json.dumps({"model": model, "msg": msg, "result": cache[(model, msg)]}) + "\n")
            res = cache[(model, msg)]
            btn, conf = llm_buttons(res, eng._prior(r.amount, r.d), eligible)
            rows.append(dict(date=r.d, merchant=r.merchant, amount=r.amount, actual=r.category,
                             baseline=" | ".join(base["buttons"]), llm=" | ".join(btn), llm_conf=conf,
                             merchant_type=res.get("merchant_type"), reason=res.get("reason"),
                             base_top1=base["buttons"][0] == r.category, base_top3=r.category in base["buttons"],
                             llm_top1=btn[0] == r.category, llm_top3=r.category in btn))
        eng.learn(r.merchant, r.amount, r.d, r.category)   # the user's tap teaches the engine

    out = pd.DataFrame(rows)
    out.to_csv(os.path.join(HERE, f"llm_backtest_{model}.csv"), index=False)
    print(f"Unknown-merchant purchases: {len(out)}   model: {model}")
    print(f"  first button right   baseline {out.base_top1.mean():.1%}   ->  LLM {out.llm_top1.mean():.1%}")
    print(f"  in the three buttons baseline {out.base_top3.mean():.1%}   ->  LLM {out.llm_top3.mean():.1%}")
    print(out.groupby("actual")[["base_top1", "llm_top1"]].mean().round(3))
    print("\nLLM misses on the first button:")
    print(out[~out.llm_top1][["merchant", "amount", "actual", "llm", "merchant_type"]].to_string(index=False))

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--show-prompt", action="store_true")
    ap.add_argument("--backtest", action="store_true")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    a = ap.parse_args()
    if a.show_prompt:
        df = pd.read_csv(os.path.join(ROOT, "data", "labeled_transactions.csv"))
        cats = load_categories(only_ids={"fun", "grocery_food", "transportation", "miscellaneous", "work_expenses"})
        print(build_system_prompt(cats, df[df.kind == "variable"]))
        print("\n--- example user message ---")
        print(build_user_message("SQ *BLANK STREET COFFEE", 6.45, dt.date(2026, 9, 26), "Chase Credit Card"))
    if a.backtest:
        backtest(a.model)
