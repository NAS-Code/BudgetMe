# Findings: data analysis and back-tests

Aggregate results from about five years of hand-labeled personal spending history (May 2021 to
September 2026). The underlying transactions stay private and are not in this repository; the numbers
below can be reproduced locally with `engine/backtest.py`.

## The dataset
- About 4,700 labeled rows: roughly 3,800 variable purchases, plus fixed bills, savings transfers,
  income, and a few manual adjustments (excluded from learning).
- About 1,260 distinct merchants after normalization. Most appear only once or twice.
- A small number of data-quality flags: template placeholders, dates outside their month's sheet, and
  a charge filed under two categories.

## Why the app has to ask
- Only **47%** of purchases come from merchants seen at least 3 times and filed in the same category
  at least 90% of the time. A rules-based app would get the rest wrong.
- Many frequent merchants are split by occasion: pharmacies, burger places, big-box stores and Venmo
  each show up in three or four categories.

## Checking the user's own rules
- **Weekday restaurants under $40 are takeout (Grocery/Food).** This holds: weekday purchases under $40
  are Grocery/Food 76% of the time, and 64% of those at $40 or more are Fun.
- **Weekends lean Fun.** Weaker than expected: Friday through Sunday is about 50/50 Fun vs.
  Grocery/Food, compared with roughly 80% Grocery/Food Tuesday through Thursday. It's implemented as a
  soft prior, and Friday is grouped with the weekend.
- **Fun week rule** (weekend-anchored): it matches 87-89% of dated Fun purchases in the most recent
  18 months. The remaining mismatches are multi-day trips, which is what the two-button ambiguity
  handling is for.

## Engine back-test (`engine/backtest.py`)
Trained on history through March 2026, then tested on 540 purchases from April through September 2026
in date order, learning from each actual category after predicting it (simulating taps).

| metric | result |
|---|---|
| first button correct | 78.5% |
| correct in top 2 | 93.3% |
| correct in the 3 buttons | 99.3% |

| category | first button | in 3 buttons |
|---|---|---|
| Grocery/Food | 98.3% | 100% |
| Transportation | 96.7% | 96.7% |
| Fun | 57.6% | 100% |
| Miscellaneous | 35.1% | 98.7% |

By tier: known merchants were right on the first button 89% of the time. Unknown merchants (22% of
purchases) used keywords and context only: 47.9% first button, 98.3% in the 3 buttons. **This is the
baseline the LLM tier needs to beat.**

Confidence calibration: purchases with confidence >= 0.95 were 40% of the total and 100% correct
(215 of 215), which is the basis for auto-filing. Between 0.85 and 0.95, 89% were correct.

Fun week: the correct week was among the offered options 87% of the time, and 29% of Fun purchases
needed the two-button choice. Every miss came from a single trip week.

Changes made because of the back-test: Misc is always the third button for unknown merchants,
Transportation is only suggested for unknown merchants whose names look like transit vendors, and
keywords count for merchants with thin history.

The Kotlin port (`app/`) replays the same back-test in `EngineGoldenTest` and matches all 540
predictions exactly.

## Caveats
- The test used hand-typed merchant names. Real bank descriptors ("SQ *SOME CAFE NEW YORK") will start
  lower until the alias map learns them.
- Older entries without dates were assigned the 15th of the month, which blurs the day-of-week signal.
- The LLM tier has not been run yet.
