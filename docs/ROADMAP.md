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
   retire the spreadsheet as the source of truth (the sheet sync stays; Nick likes the sheet).

## Spreadsheet sync (built)
Two-way sync with a Google Sheets copy of the tracker (`Sheets.kt`). Needs a Google Cloud OAuth client (Android,
package `com.budgetme` + signing SHA-1) with the Sheets API enabled. For other users: app-created sheets in a
simple layout with the `drive.file` scope, which avoids Google's verification review.

## Bank balance (built)
Income and Monthly lines (estimate vs actual), synced with the sheet's sections; card charges are "on card" until a
recorded card payment marks them paid (sheet: Credit Card column -> WellsFargo column, in the month they were charged);
the Month tab shows the Wells Fargo balance exactly as the tracker's M14 computes it (verified: the app's September
balance equals the sheet's carry-over into October), plus "Check against bank".
Next: fill income and bill actuals from Wells Fargo deposit / bill-pay alerts once real samples are captured.

## iPhone version (planned, not started)
iOS doesn't let apps read other apps' notifications or texts, so the Android capture path can't be ported. Three
sources instead, merged by the same matching as the SimpleFIN sync (one purchase seen twice becomes one entry):

| Source | Speed | Covers |
|---|---|---|
| Bank text alerts -> Shortcuts "Message" automation -> app's "Log bank alert" App Intent | seconds | every card purchase |
| Wallet "Transaction" automation (merchant, amount, card) | instant | Apple Pay taps only |
| Teller, polled every ~10 min by a small server, pushed to the phone | minutes | everything, the backstop |

- **Test first, no code:** on an iPhone, make a Message automation (sender = Chase's alert number, Run Immediately)
  whose action is Show Notification with Shortcut Input. If the notification shows the alert text, the text path
  works. Not confirmed in Apple's docs; locked-phone behavior is also unconfirmed. If it fails, Wallet + Teller.
- **Teller pricing** (teller.io, Oct 2026): Transactions $0.30 per enrollment per month, flat, so a 10-minute
  poll costs the same as daily; free developer tier with 100 live connections. Balance is $0.10 per call: never poll
  it. Rate limits aren't published; back off to 15-30 min if throttled. "Live, real-time data" is Teller's claim.
- **Needs:** a server (iOS background refresh runs when iOS decides, so the phone can't poll every 10 min; Teller
  access tokens and webhooks belong server-side), a Mac with Xcode, Apple Developer account ($99/yr).
- **Port:** engine, alert parsers (bank texts are usually worded like the push alerts), sheet sync and LLM tier.
  Consider Kotlin Multiplatform to share them instead of rewriting in Swift; SwiftUI screens either way.
- **Rejected:** Plaid (refreshes 1-4x/day; on-demand refresh is paid per call), storing bank logins to scrape
  (MFA breaks it, banks block it, can void fraud protection), FinanceKit (Apple Card/Cash/Savings only).
- Android is unchanged: bank push alerts in real time + SimpleFIN daily.

## Later
- Alias learning improvements (processor prefix stripping, store numbers)
- Splitting multi-item purchases using order emails (Amazon, Target)
- Per-category weekly budgets for other users
- Export to Excel or Google Sheets
