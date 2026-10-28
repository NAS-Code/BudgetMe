# Switching phones

BudgetMe keeps your data in two places: the app's database (backed up by Android to your Google account)
and your Google Sheet. The app's Settings > Switching phones has the same checklist, with live checkmarks.

## What's backed up
| What | Where | Restored automatically? |
|---|---|---|
| Purchases, categories, merchant learning, Income/Monthly lines | Android Auto Backup (your Google Drive, encrypted) | Yes |
| Settings (mode, auto-file threshold, bank apps, sheet link) | Android Auto Backup | Yes |
| Anthropic API key | Not in the cloud backup (`res/xml/backup_rules.xml`) | Only with a phone-to-phone transfer |
| Notification access | System setting | No, turn it on again |
| Google account connection for the sheet | Google Play services | Usually; if sync fails, tap Reconnect Google |
| Filed purchases by month | Your Google Sheet | Synced back for last, this and next month |

## Before you switch (old phone)
1. Open the app once so recent purchases are saved.
2. Android Settings > Google > Backup > **Back up now**. To check from the computer:
   `adb shell bmgr backupnow com.budgetme` should end with `Success`.
3. If you use the sheet, open BudgetMe > Settings > Google Sheet > **Sync now**.

## On the new phone
1. During phone setup, sign in with the same Google account and **restore from the old phone's backup**
   (cloud or cable).
2. BudgetMe isn't from the Play Store, so the phone won't reinstall it. Turn on USB debugging, connect it, and
   from **the same computer** run:
   ```
   export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   ./gradlew installDebug
   ```
   Android restores the app's data at install. If the app opens to the first-time setup screen instead,
   close it and restore by hand:
   ```
   adb shell bmgr list sets                    # find the old phone's token
   adb shell bmgr restore <token> com.budgetme
   ```
3. Open BudgetMe, tap the notification-access card on the Inbox (or Settings > Notification access settings)
   and allow BudgetMe.
4. Turn on purchase alerts (lowest threshold, push notifications) in the Chase, Wells Fargo and Venmo apps.
5. Settings > Google Sheet: check the link is there, tap **Connect Google account** if it says so, then **Preview**.
6. Settings > New merchants: paste the Anthropic API key again (skip if you did a phone-to-phone transfer).

## New computer too? Keep the signing key
The app is signed with the computer's debug key, `~/.android/debug.keystore`
(`C:\Users\<you>\.android\debug.keystore`). The Google Sheets sign-in (OAuth client, registered with that key's
SHA-1) and app updates both depend on it. Copy that file to the new computer before you install from it.
If it's lost: uninstall and reinstall the app (data comes back from the backup or the sheet), and add the new
SHA-1 to the Google Cloud OAuth client (`./gradlew signingReport`).

## If the backup is missing
- The sheet still has your filed purchases; connect it and they sync back for last, this and next month. Do this
  right after setup, before turning on notification access, so the sheet's rows come in before new purchases do.
  (Rows written by the old phone are imported, never blanked: the sync knows those ids weren't issued on this one.)
- Older history: Settings > History > import `data/labeled_transactions.csv`
  (`python scripts/extract_history.py` rebuilds it from the tracker workbook).
