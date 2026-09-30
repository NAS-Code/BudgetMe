"""Dump golden cases for the Kotlin port.

  data/golden/backtest_events.csv  - the online back-test as a replayable event log (private: real merchants)
  app/src/test/resources/fun_weeks.csv - fun_week_options() for every day 2020-2031 (no private data)

Same data prep as backtest.py, so replaying the log must reproduce 78.5% top-1.
"""
import os, csv, datetime as dt
import pandas as pd
import predictor as P

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
df = pd.read_csv(os.path.join(ROOT, 'data', 'labeled_transactions.csv'), parse_dates=['date'])
df['date'] = df.date.dt.date.where(df.date.notna(), None)
df['flags'] = df['flags'].fillna('')
v = df[(df.kind == 'variable') & (~df['flags'].str.contains('placeholder'))].copy()

def eff_date(r):
    y, m = map(int, r.month.split('-'))
    if r.date is not None and not pd.isna(r.date) and r.date.strftime('%Y-%m') == r.month: return r.date
    return dt.date(y, m, 15)

v['d'] = v.apply(eff_date, axis=1)
v = v.sort_values(['d', 'source'])
train = v[v.month < '2026-04']; test = v[(v.month >= '2026-04') & (v.month <= '2026-09')]

def fw(opts): return ' '.join(f'{m}:{w}' for m, w in opts) if opts else ''

os.makedirs(os.path.join(ROOT, 'data', 'golden'), exist_ok=True)
m = P.Predictor()
with open(os.path.join(ROOT, 'data', 'golden', 'backtest_events.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.writer(f)
    w.writerow(['op', 'merchant', 'amount', 'date', 'category', 'buttons', 'confidence', 'tier', 'fun_weeks'])
    for r in train.itertuples():
        m.learn(r.merchant, r.amount, r.d, r.category)
        w.writerow(['learn', r.merchant, r.amount, r.d.isoformat(), r.category, '', '', '', ''])
    for r in test.itertuples():
        p = m.predict(r.merchant, r.amount, r.d)
        w.writerow(['predict', r.merchant, r.amount, r.d.isoformat(), r.category, '|'.join(p['buttons']),
                    p['confidence'], p['tier'], fw(p['fun_week_options'])])
        m.learn(r.merchant, r.amount, r.d, r.category)

out = os.path.join(ROOT, 'app', 'src', 'test', 'resources')
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, 'fun_weeks.csv'), 'w', newline='') as f:
    w = csv.writer(f); w.writerow(['date', 'options'])
    d = dt.date(2020, 1, 1)
    while d <= dt.date(2031, 12, 31):
        w.writerow([d.isoformat(), fw(P.fun_week_options(d))]); d += dt.timedelta(days=1)
print('wrote', len(train), 'learn +', len(test), 'predict events')
