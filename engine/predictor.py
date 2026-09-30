"""
Budget categorizer - prediction engine (v1).

predict(merchant, amount, date) -> ranked category buttons + Fun week options.

Tiers, in order:
  1. history  - you've filed this merchant (or a close spelling) before
  2. keywords - merchant name contains a word tied to a category description
  3. context  - nothing known about the merchant: day-of-week + amount prior
                (in the app, an LLM call using the category descriptions replaces tiers 2-3)
All tiers are blended with a day-of-week/amount prior so the same merchant can
rank differently on a Tuesday lunch vs. a Saturday night.
"""
import re, unicodedata, math, json, calendar, datetime as dt
from collections import defaultdict
from difflib import get_close_matches

GENERIC = {'deli','market','bodega','bagel','bagels','grocery','cafe','restaurant','inc','nyc','the','pharmacy'}

def merchant_key(name: str) -> str:
    s = unicodedata.normalize('NFKD', name.lower())
    s = ''.join(ch for ch in s if not unicodedata.combining(ch))
    s = re.sub(r'\(.*?\)', ' ', s).replace('&', ' and ')
    s = re.sub(r'[^a-z0-9 ]', ' ', s)
    w = s.split(); w2 = [t for t in w if t not in GENERIC] or w
    k = ''.join(w2)
    return k[:-1] if len(k) > 4 and k.endswith('s') else k

# Signals pulled from the category descriptions. The LLM tier would supersede these.
KEYWORDS = {
    'Transportation': ['uber','lyft','mta','subway','clipper','citibike','citi bike','lirr','metro north','metronorth',
                       'nj transit','path','sfmta','meter','amtrak','taxi','cab','parking','toll','sfmta','bart','revel','lime','bird'],
    'Grocery/Food':   ['grocery','groceries','market','bodega','deli','food','foods','trader joe','whole foods','key food',
                       'foodtown','safeway','wegmans','stop and shop','fairway','gourmet','bagel','dunkin','starbucks'],
    'Fun':            ['bar','pub','liquor','liqur','spirits','tavern','brewery','beer','wine','winery','cellar','lounge','club','cinema','amc',
                       'theater','theatre','bowling','karaoke','games','gaming','comics','records','tickets','dice',
                       'seatgeek','ticketmaster','steam','museum','concert'],
}
AUTO = ['Fun', 'Grocery/Food', 'Transportation', 'Miscellaneous']
MANUAL = {'Work Expenses'}

def amount_bucket(a):  # matches the $40 weekday rule
    a = abs(a)
    return 'lt15' if a < 15 else 'lt40' if a < 40 else 'lt100' if a < 100 else 'ge100'

def day_type(d):  # Fri counts with the weekend: history shows Fri behaves like Sat/Sun
    return 'wkend' if d.weekday() >= 4 else 'wkday'

# ---------- Fun week rule ----------
def weekends(y, m):
    """(saturday, sunday) pairs owned by month y/m: a weekend belongs to the month its Saturday is in."""
    out = []
    for d in range(1, calendar.monthrange(y, m)[1] + 1):
        day = dt.date(y, m, d)
        if day.weekday() == 5:
            out.append((day, day + dt.timedelta(days=1)))
    return out

def fun_week_options(d: dt.date):
    """Returns list of (budget_month 'YYYY-MM', week_no). One entry = unambiguous, two = offer both."""
    prev = (d.replace(day=1) - dt.timedelta(days=1))
    cands = []
    for (y, m) in [(prev.year, prev.month), (d.year, d.month)]:
        for i, (sat, sun) in enumerate(weekends(y, m), 1):
            cands.append((f'{y}-{m:02d}', i, sat, sun))
    nxt = dt.date(d.year + (d.month == 12), d.month % 12 + 1, 1)
    for i, (sat, sun) in enumerate(weekends(nxt.year, nxt.month), 1):
        cands.append((f'{nxt.year}-{nxt.month:02d}', i, sat, sun))
    cands.sort(key=lambda c: c[2])
    # default: the week ending on the next weekend (or the weekend itself)
    default = next(c for c in cands if d <= c[3])
    opts = [(default[0], default[1])]
    idx = cands.index(default)
    prev_wk = cands[idx - 1] if idx > 0 else None
    if d.weekday() >= 5 or not prev_wk:
        return opts                          # a Saturday/Sunday always belongs to its own weekend
    after_last = prev_wk[0] != default[0]    # past the last weekend of prev_wk's month
    just_after = (d - prev_wk[3]).days <= 3  # Mon-Wed right after a weekend (trip/outing continued)
    if after_last or just_after:
        opts = [(prev_wk[0], prev_wk[1])] + opts
    return opts

# ---------- model ----------
class Predictor:
    def __init__(self, half_life_days=365, prior_weight=2.0):
        self.hl = half_life_days; self.pw = prior_weight
        self.hist = defaultdict(list)          # key -> [(date, category)]
        self.ctx = defaultdict(lambda: defaultdict(float))   # (daytype, bucket) -> cat -> count
        self.aliases = {}                      # bank descriptor key -> merchant key (learned from taps)

    def learn(self, merchant, amount, date, category):
        k = self.aliases.get(merchant_key(merchant), merchant_key(merchant))
        self.hist[k].append((date, category))
        self.ctx[(day_type(date), amount_bucket(amount))][category] += 1

    def _prior(self, amount, date):
        c = self.ctx[(day_type(date), amount_bucket(amount))]
        tot = sum(c.values()) or 1
        return {cat: (c.get(cat, 0) + 1) / (tot + len(AUTO)) for cat in AUTO}

    def _lookup(self, key):
        if key in self.hist: return key, 'history'
        if len(key) >= 4:
            m = get_close_matches(key, self.hist.keys(), n=1, cutoff=0.85)
            if m: return m[0], 'history (close match)'
            pref = [k for k in self.hist if len(k) >= 5 and (key.startswith(k) or k.startswith(key))]
            if pref: return max(pref, key=lambda k: len(self.hist[k])), 'history (close match)'
        return None, None

    def predict(self, merchant, amount, date, n=3):
        key = self.aliases.get(merchant_key(merchant), merchant_key(merchant))
        prior = self._prior(amount, date)
        mk, tier = self._lookup(key)
        low = merchant.lower()
        kw = [cat for cat, words in KEYWORDS.items()
              if any(re.search(r'\b' + re.escape(w) + r'\b', low) for w in words)]
        scores = defaultdict(float)
        if mk:
            w = 1.0 if tier == 'history' else 0.5          # a close spelling might be a different place
            for d, cat in self.hist[mk]:
                scores[cat] += w * 0.5 ** (max((date - d).days, 0) / self.hl)
            seen = sum(scores.values())
            for cat in AUTO:                                # blend in context so Tue lunch != Sat night
                scores[cat] += self.pw * prior[cat] * (1 if seen < 5 else 0.5)
            if seen < 5:
                for cat in kw: scores[cat] += 1.0           # thin history: let the name speak too
        else:
            for cat in AUTO:
                scores[cat] = prior[cat] + (1.0 if cat in kw else 0)
            if 'Transportation' not in kw:
                scores['Transportation'] *= 0.1             # transit vendors are a known set; don't guess it blind
            # Misc is the "not sure" bucket: always keep it as the last button for unknown merchants
            top2 = sorted([c for c in AUTO if c != 'Miscellaneous'], key=lambda c: -scores[c])[:2]
            if 'Miscellaneous' not in top2:
                scores['Miscellaneous'] = min(scores[top2[1]] - 1e-6, max(scores['Miscellaneous'], scores[top2[1]] * 0.99))
            tier = 'keywords' if kw else 'context only (LLM in app)'
        conf = max(scores.values()) / sum(scores.values())
        ranked = [c for c, _ in sorted(scores.items(), key=lambda x: -x[1]) if c not in MANUAL or mk]
        ranked = ranked[:n]
        return {'buttons': ranked, 'confidence': round(conf, 2), 'tier': tier,
                'fun_week_options': fun_week_options(date) if 'Fun' in ranked else None}
