# Budget Categorizer: Product Spec

## 1. Problem

Nick tracks spending in an Excel workbook, one tab per month since May 2021. Every purchase is
typed in by hand and filed under a category. His categories reflect **intent**: a Whole Foods run
can be Fun, a burger can be Grocery/Food or Fun, and a Venmo payment can be anything. Existing
apps (Monarch, Copilot, Simplifi, YNAB) categorize by merchant, which gets roughly half of his
spending wrong, and they categorize a day or more after the fact, when he no longer remembers why
he bought something.

## 2. Goal

Every purchase on his **Wells Fargo checking account** and **Chase credit card** (plus Venmo
payments) triggers a phone notification within seconds. He files it with one tap, or the app files
it automatically when it's confident. The monthly view reproduces his spreadsheet: fixed bills,
weekly Fun buckets, variable categories, and remaining budget.

Non-goals for v1: investment tracking, bill pay, multi-user sync, iOS.

## 3. Users and platform

- Nick first; the design should allow other users with their own categories.
- **Android only.** Real-time capture depends on `NotificationListenerService`, which iOS doesn't
  allow.
- Suggested stack: Kotlin, Jetpack Compose, Room (SQLite), WorkManager for the daily sync. Not
  decided; see Open Decisions.

## 4. Architecture

```
 Chase / Wells Fargo / Venmo push alert
            |
            v
 NotificationListenerService --> alert parser (per bank) --> Transaction (pending)
                                                                   |
                                                                   v
                                               Prediction engine (history -> keywords -> LLM)
                                                                   |
                                  confidence >= 0.95 ------------+------------ otherwise
                                          |                                        |
                             auto-file + "Filed as X · Undo"        notification with up to 3 buttons
                                                                   |
                                                                   v
                                                user tap -> category, Fun week, learn()

 Daily (WorkManager): SimpleFIN sync -> match settled txns to pending ones -> update amounts,
                      add missed txns to "Needs review", drop transfers
```

### 4.1 Capture: bank alerts (real time)
- Nick turns on purchase alerts in the Chase, Wells Fargo, and Venmo apps with the lowest threshold,
  delivered as push notifications.
- The listener filters by package name and parses merchant, amount, account, and (for Venmo) the
  payment note. **Alert text formats are not yet known.** Collect real samples before writing the
  parsers, and keep each parser isolated and unit-tested because banks change wording.
- Fallback option if notification parsing proves brittle: email alerts -> Gmail push -> small
  backend -> FCM. Not planned for v1.

### 4.2 Reconciliation: daily aggregator sync
- **SimpleFIN Bridge** ($1.50/month or $15/year, user-generated access token, no developer API
  key). It connects both checking and credit card accounts. Lunch Money's API is an alternative if
  a hosted UI is ever wanted.
- Matching: same account, amount within tolerance (tips change the final amount), date within
  about 3 days, fuzzy merchant match. On match, update to the settled amount and keep the user's
  category.
- Unmatched settled transactions go to **Needs review** (recurring bills, charges with no alert).
- **Transfers**: a Wells Fargo payment to the Chase card shows up on both accounts. Detect it and
  exclude it from spending, or card spending is counted twice.
- **Refunds** are negative amounts and net against the original purchase's category.

### 4.3 Fixed monthly bills
Rent, utilities, internet, loans, gym, subscriptions, etc. (see the "Monthly: ..." subcategories in
`data/labeled_transactions.csv`). Match these by merchant plus approximate amount **before** the
prediction engine runs, and file them as fixed expenses without prompting. Savings, Crypto, Travel
Fund, and Danger Fund are transfers to savings, not spending.

## 5. Categories

Defined in `config/categories.json`. Each has:

| field | meaning |
|---|---|
| `id` | stable internal id. History links to this, so renames don't lose data |
| `name`, `description` | user's own words. The description is sent to the LLM |
| `budget.amount`, `budget.period` | `month` (calendar) or `weekend_month` (see Fun week rule) |
| `budget.track_by` | `month` or `week` (Fun is tracked in weekly buckets) |
| `hints` | soft signals such as `weekend_boost` and `weekday_restaurant_under: 40` |
| `predict` | `auto` · `fallback_only` (Misc: never guessed from uncertainty) · `manual_only` (Work Expenses: never auto-predicted until the merchant's own history shows it) |
| `archived` | hidden from buttons, history kept |

Nick's active set: **Fun, Grocery/Food, Transportation, Miscellaneous, Work Expenses.** The file
also includes optional defaults for other users: Fitness, Shopping, and Travel (Travel's
description is a draft for Nick to confirm).

Category management must support add, edit, archive, **rename** (id unchanged), and **merge**
(move history to the target id). A new category has no history, so it's predicted from its
description until taps accumulate.

Nick's descriptions, verbatim in intent:
- **Fun**: bars, social venues, restaurants, stores selling goods or services for hobbies. Weekend
  purchases lean Fun. Bodegas are 50/50 Fun vs. Grocery/Food.
- **Grocery/Food**: grocery stores and bodegas. Restaurant purchases under $40 on a weekday are
  likely takeout.
- **Transportation**: transportation vendors (Uber, Lyft, MTA, Clipper, Citi Bike).
- **Miscellaneous**: anything that doesn't clearly fit another fixed or variable category.
- **Work Expenses**: anything bought for the job. It overlaps with everything, so the user decides.

## 6. Fun week rule

Fun has a monthly budget split into one bucket per weekend.

1. **A weekend belongs to the month its Saturday falls in.** If a month starts on a Sunday, that
   Sunday belongs to the previous month's last weekend. October 31, 2026 (Saturday) and November 1
   (Sunday) are both October's Week 5.
2. **Week N** covers the days after weekend N-1 through the end of weekend N. So the days leading
   up to the first weekend, plus that weekend, are Week 1. A month has 4 or 5 Fun weeks.
3. **Ambiguous days get two buttons** (e.g. "Fun · Jul Wk 4" and "Fun · Aug Wk 1"):
   - weekdays after the month's last weekend (this month's last week or next month's Week 1,
     depending on whether the purchase is for the coming weekend), and
   - Monday through Wednesday right after a weekend (a trip or outing that continued).
4. Saturdays and Sundays are never ambiguous.
5. **Only Fun uses this rule.** Other categories use the calendar month. Every transaction stores
   `budget_month` separately from `date`.

Reference implementation: `fun_week_options()` in `engine/predictor.py`. Its edge cases were checked
against Aug 1, Aug 17, Jul 28, Sep 2, Oct 31, Nov 1 2026, and Mar 1 2026.

## 7. Prediction engine

Reference: `engine/predictor.py`. Input is `(merchant, amount, date)` plus optional account and
note. Output is ranked buttons, a confidence score, the tier used, and Fun week options.

1. **Normalize** the merchant into a key: lowercase, strip accents, punctuation, and parentheticals,
   drop generic words (deli, market, bodega, pharmacy...), drop a trailing "s". Apply the learned
   **alias map** first: bank descriptor key -> known merchant key, learned from the user's first tap
   on a new descriptor.
2. **History tier**: exact key match, or a close match (difflib ratio >= 0.85 or a prefix match,
   weighted 0.5 because it might be a different place). Category counts are recency-weighted
   (365-day half-life) and blended with a context prior (day type Fri-Sun vs. Mon-Thu, times amount
   bucket <15 / <40 / <100 / 100+). Weaken the prior when the merchant has been seen 5+ times. With
   thin history (<5), keyword hits also count.
3. **Unknown merchant**: an LLM call using the category descriptions (`engine/llm_tier.py`). Its
   probabilities are blended 85/15 with the context prior. The current placeholder, used until the
   LLM is wired in: keyword lists plus the context prior. Transportation is only suggested when the
   name matches a transit keyword, and **Misc is always the third button** for unknown merchants.
4. **Output**: up to 3 buttons, never a `manual_only` category unless the merchant's history
   contains it. If Fun is among the buttons, include the Fun week options; when there are two,
   split the Fun button in two.
5. **Learning**: every confirmed tap calls `learn()` (history plus context prior). Undo on an
   auto-filed transaction must reverse its learning.

### LLM tier
- Default model `claude-haiku-4-5-20251001`, one call per unrecognized merchant, with the system
  prompt cached. Temperature 0.
- The system prompt is **generated** from `categories.json` and history: category definitions,
  distinctive examples per category (merchants filed there at least 80% of the time, seen at least
  3 times), merchants the user splits by occasion, decision steps (identify the business, strip
  processor prefixes like `SQ *`/`TST*`, apply timing and amount rules, Misc as fallback, Venmo
  categorized by its note), and a strict JSON output format.
- Response: `{merchant_type, ranking:[{category, probability}], reason}`. Store `merchant_type` so
  it can be shown in the app and reused.

## 8. Notification and app UX

- **Needs input**: title "$23.40 · Blank Street" with up to 3 action buttons. Tapping the body opens
  the app for the full category list, Work Expenses, split transactions, and notes.
- **Auto-filed** (confidence >= 0.95): "Filed as Grocery/Food · Undo". It stays quiet, with no sound.
- **Screens**: Inbox (needs review), Month view (spreadsheet layout: income, fixed bills, Fun weeks
  1-5 with budget remaining, each variable category, surplus), Transaction detail, Categories
  (CRUD, descriptions, budgets, period, prediction mode), Settings (accounts, alert sources,
  SimpleFIN token, API key or backend URL, auto-file threshold).
- **Split transactions** in the app (a Target run that's part groceries, part home goods).

## 9. Data model (suggested)

```
Transaction   id, source(alert|feed|manual), account, raw_descriptor, merchant_key, amount_cents,
              pending_amount_cents, date, budget_month, category_id, fun_week, status(auto_filed|
              confirmed|needs_review|transfer|excluded), confidence, tier, note, feed_txn_id,
              parent_id (for splits), created_at, updated_at
Category      as in categories.json
MerchantAlias descriptor_key -> merchant_key
MerchantStat  merchant_key, category_id, weighted_count, last_seen   (or derive from Transaction)
FixedBill     name, merchant_key, expected_amount_cents, tolerance, category/subcategory
```

Seed Transaction/MerchantStat from `data/labeled_transactions.csv` (kind = variable) on first run.

## 10. Privacy
- Financial data stays on the device (or on Nick's own backend if one is added).
- The LLM receives only merchant, amount, date, account label, and note. No balances or history
  beyond the example merchant names in the system prompt.
- `data/` is gitignored. Never commit it to a public repository.

## 11. Open decisions
1. **Where the LLM call runs**: directly from the phone with Nick's API key (simplest, fine for a
   personal app) or through a small backend (required if the app is ever shared).
2. **Alert source**: parsing push notifications (planned) vs. email alerts through a backend.
3. **Export**: whether to also write each month to Excel or Google Sheets during the side-by-side
   month.
4. **Travel** category description: confirm or rewrite the draft.
5. **Auto-file threshold**: 0.95 back-tested at 100% precision on 215 transactions. Revisit once
   real bank descriptors are flowing.
