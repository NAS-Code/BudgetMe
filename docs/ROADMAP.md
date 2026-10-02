# Roadmap

## Done
- [x] Market check: no existing app does intent-based, real-time, one-tap categorization with custom categories
- [x] Architecture: bank push alerts for real time, daily SimpleFIN sync for reconciliation
- [x] Extracted 5 years of labeled history from the Excel tracker (`scripts/extract_history.py`)
- [x] Category schema and Nick's category descriptions (`config/categories.json`)
- [x] Fun week rule defined, implemented, and verified against history
- [x] Reference prediction engine and back-test (78.5% first button, 99.3% in 3 buttons)
- [x] LLM tier prompt and back-test script (`engine/llm_tier.py`)
- [x] Android MVP (`app/`): Kotlin port of the engine, golden-tested against the Python back-test (all 540
      predictions identical, 4,383 Fun-week dates identical); Room DB with history import; notification
      listener + provisional parser that logs every bank alert raw; 3-button notifications with Fun week split;
      auto-file >= 0.95 with Undo (reverses learning); Inbox, Month (Fun by weekend), Categories (add, edit,
      rename, archive, merge), Settings (notification access, import, threshold, captured alerts)

## Next
1. **Other users (in progress):** setup screen (starter or weekend-budgeter categories, light/strict mode,
   bank apps), light mode, and the LLM tier in the app with the user's own API key are built. Still needed:
   real alert samples for the untested bank apps, a small backend so users don't bring API keys, and a
   Play Store listing (notification-listener review).
1b. **Run the LLM back-test** with an API key: `python engine/llm_tier.py --backtest`. Compare with the
   47.9% first-button baseline on unknown merchants, then adjust the prompt or category descriptions
   based on the listed misses. Optionally compare Haiku with Sonnet.
2. **Collect alert samples.** Nick turns on push alerts (lowest threshold) in the Chase, Wells Fargo,
   and Venmo apps. With the app installed and notification access on, every alert is logged in
   Settings > Captured alerts ("Copy all"). Replace the guessed wordings in `AlertParseTest` with the real
   ones and tighten `parseAlert()` into one parser per bank.
3. **Android MVP follow-ups**: split transactions; income and fixed bills in the Month view (they come with
   steps 5-6); on-device check of the notification flow with real alerts.
4. **LLM integration**: done on-device with the user's key (`Llm.kt`). A backend proxy is needed before
   sharing the app widely.
5. **SimpleFIN daily sync**: matching, settled amounts, Needs review list, transfer detection, refunds.
6. **Fixed bill matching** before prediction.
7. **Side-by-side month**: run the app alongside the spreadsheet for one month, compare totals, then
   retire the spreadsheet.

## Later
- Alias learning improvements (processor prefix stripping, store numbers)
- Splitting multi-item purchases using order emails (Amazon, Target)
- Per-category weekly budgets for other users
- Export to Excel or Google Sheets
