# Budget Categorizer: context for Claude Code

Personal Android app for Nick (NYC) that catches every purchase as it happens and asks him to
categorize it with one tap from the notification. It replaces a manual Excel tracker he has kept
since May 2021 (`data/source/Monthly_Budget_Tracker.xlsx`).

Read `docs/SPEC.md` before building anything. `docs/FINDINGS.md` has the data analysis and
back-test numbers the design is based on (public, aggregate-only version; `private/FINDINGS.md`, if
present, has the full detail with merchant names). `docs/ROADMAP.md` has what's done and what's next.

## Core ideas (don't lose these)
- **Categories are intent-based, not merchant-based.** The same merchant (CVS, Venmo, a burger place)
  goes in different categories depending on the occasion. That is why the app asks, and why
  rules-only apps failed him.
- **Categories are data, not code.** `config/categories.json` defines them (name, description,
  budget, period, prediction mode). Users can add/edit/archive categories. The LLM prompt is
  generated from this file. Never hardcode Nick's five categories in app logic.
- **Real time comes from bank push alerts, not an aggregator.** Android NotificationListenerService
  reads Chase / Wells Fargo / Venmo notifications. A daily aggregator sync (SimpleFIN) is only the
  reconciliation backstop.
- **Fun is budgeted by weekend, not calendar week.** A weekend belongs to the month its Saturday is
  in. See SPEC section "Fun week rule". Fun purchases can count toward a different month than their
  date; every transaction stores `budget_month` separately from `date`.
- **High-confidence predictions are auto-filed.** Strict mode (>= 0.95): Undo notification, everything else
  gets up to 3 category buttons. Light mode (>= 0.80, for people who don't track closely): files silently,
  the rest wait in the Inbox behind one quiet "N to review" notification.

## Repo layout
```
config/categories.json          category definitions (the original weekend-budgeter set + optional extras)
config/starter_categories.json  simpler set offered at setup to new users
data/source/                    original Excel tracker (private, do not commit to a public repo)
data/labeled_transactions.csv   4,700 labeled rows extracted from the tracker (training history)
data/merchant_lookup.csv        per-merchant category stats
data/budget_history_labeled.xlsx same data, human-readable, with a Notes tab
scripts/extract_history.py      rebuilds data/* from the source workbook (deterministic)
engine/predictor.py             reference prediction engine (Python) + Fun week rule
engine/backtest.py              replays Apr-Sep 2026 against the engine; prints accuracy
engine/llm_tier.py              LLM step for unknown merchants: prompt builder + back-test
engine/backtest_results.csv     latest back-test output
engine/export_golden.py         writes golden cases for the Kotlin port (data/golden/, app/src/test/resources/)
app/src/main/java/com/budgetme/ Android app (Kotlin, Compose, Room)
  Engine.kt   port of predictor.py (merchant keys, difflib ratio, Fun week rule, Predictor)
  Seed.kt     CSV reader, categories.json -> Category rows, history import (same order/dates as backtest.py)
  Db.kt       Room: Txn, Category, MerchantAlias, RawAlert
  Brain.kt    in-memory model, ingest -> auto-file or ask, file/undo/merge, notifications + action receiver
  Alerts.kt   NotificationListenerService, supported bank apps, Chase/WF parsers + generic fallback
  Budget.kt   bank balance (= the tracker's M14), card payment picking, Income/Monthly line sync plan
  BudgetUi.kt balance card, Income/Monthly lines, card payment and bank check dialogs
  Sheets.kt   two-way Google Sheets sync: tracker-layout parser, reconcile plan (pure, tested), Sheets API + sign-in, WorkManager
  Llm.kt      port of llm_tier.py: prompt builder, Claude call (Java SDK, user's own key), reply parser
  Ui.kt       Setup (categories, light/strict, bank apps), Inbox, Month, Categories, Settings
app/src/test/                   EngineGoldenTest (replays the back-test), AlertParseTest, LlmTest, SheetsTest, BudgetTest
```

## Commands
```
pip install -r requirements.txt
python scripts/extract_history.py            # regenerate data/ from the workbook
python engine/backtest.py                    # engine accuracy (expect top1 ~78.5%, top3 ~99.3%)
python engine/llm_tier.py --show-prompt      # print the LLM system prompt (no API call)
ANTHROPIC_API_KEY=... python engine/llm_tier.py --backtest   # LLM step on unknown merchants
python engine/export_golden.py               # after any predictor.py change, then re-run the Kotlin tests

# Android (JDK = Android Studio's bundled jbr)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testDebugUnitTest                  # golden tests: must match Python exactly, top1 78.5%
./gradlew installDebug                       # phone connected with USB debugging
```

## Working rules
- The Python engine is the **reference implementation**. When porting to Kotlin, keep behavior
  identical and use `engine/backtest.py` outputs as golden test cases (same inputs -> same buttons).
- Any change to prediction logic must be re-run through `engine/backtest.py`; report top-1 / top-3
  before and after.
- Don't delete or rewrite `data/source/`. Regenerate derived data with the script instead.
- Sheet sync: column A of the spreadsheet holds app row ids (#123). Txn.sheetHash is the state both sides agreed on;
  never write history rows back, never delete more than Sheets.MAX_DELETES in one sync.
- Money: store amounts as integer cents in the app DB. Refunds are negative amounts.
- Keep Nick's financial data local to the device (or his own backend). No third-party analytics.
- **The GitHub repo is public.** Never commit real transactions, merchant names from the history, trip
  details, or account numbers. Real data lives only in the gitignored `data/`, `private/`, and back-test
  output files. Test fixtures use made-up merchants; real alert samples must be anonymized (swap in
  fake names, amounts, and card digits) before they go into `AlertParseTest`.
