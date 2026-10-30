# AskAll

Ask one question and it goes to every AI app on your Android phone (ChatGPT, Gemini, Claude,
Copilot, Perplexity, Grok, DeepSeek, …). When all replies are in, Gemini is asked which one is
best, and AskAll shows you the winner.

**No API keys.** AskAll doesn't talk to any AI service itself. It uses Android's Accessibility
Service to operate the apps already installed and signed in on your phone, the same way you would:
open the app, type the question, press send, read the reply.

## What you need

- An Android phone (Android 8.0 or newer) with the AI apps installed **and signed in**.
- Gemini installed (it is the judge). If it isn't, you still get every reply, just no winner.
- Either a computer with **Android Studio**, or a free **GitHub** account (to build the APK online).

## Build the app

### Option A: Android Studio (simplest)

1. Install Android Studio, then **File → Open** and choose this `AskAll` folder.
2. Let it sync (accept any "update Gradle/plugin" suggestions).
3. Plug in your phone (USB debugging on) and press **Run ▶**, or use
   **Build → Build APK(s)** and copy `app/build/outputs/apk/debug/app-debug.apk` to your phone.

### Option B: GitHub builds the APK for you

1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab → **Build APK** → **Run workflow**.
3. When it finishes, download the **AskAll-debug-apk** artifact, unzip it and install `app-debug.apk`.

## First-time setup on the phone

1. Install the APK (allow "install unknown apps" for your browser/files app when asked).
2. Open **AskAll** and tap **Open Accessibility** → **Installed apps** → **AskAll** → turn it **On**.
3. If Android says **Restricted setting**: Settings → Apps → AskAll → ⋮ (top right) →
   **Allow restricted settings**, then turn the service on again. (Android does this for every
   app installed outside the Play Store.)
4. Open each AI app once yourself and finish any sign-in or welcome screens.

## Using it

1. Tick the AI apps you want (installed ones are detected; use **+ Add another app** for others).
2. Type your question and tap **Ask all**.
3. AskAll opens each app in turn, a small "Asking ChatGPT… tap to stop" bubble appears at the top,
   then it opens Gemini to judge, then brings you back here with the best answer and every reply.

## Things to know (please read)

- **It isn't silent background work.** Android doesn't let one app quietly type into another, so each
  AI app comes on screen briefly, one after another. Don't use the phone until it's done; tap the
  bubble to stop.
- **It can break.** It finds the message box and Send button by looking at the screen, not through
  an official interface. If an AI app changes its layout, that app may start failing until the
  matching heuristic is adjusted (see `NodeUtils.kt`, `ChatDriver.kt`).
- **Only on-screen text is read.** Very long answers can be cut off, which is why "Ask for short
  answers" is on by default (it adds "under 150 words" to your question).
- **It resets each AI app to its main screen** before asking, so you start a fresh chat each time.
  This will also close whatever you had open inside that app.
- **Your questions go to every AI app you tick**, and the replies go to Gemini, under each app's own
  account and terms. Don't ask anything you wouldn't send to all of them.
- **Privacy of the service itself.** An accessibility service *can* read any screen, so only install
  builds you trust. This one only reads the screen of the AI app it is currently asking, only while a
  run is active, and stores nothing except your app selection. It has no internet permission and
  makes no network calls. You can read all of the code: it is about 1,200 lines.
- Some apps may block automated typing or sign you out; AI companies' terms may restrict automated
  use. This is meant for your own personal convenience.
- The Play Store generally doesn't accept apps like this, so it is installed directly (sideloaded).

## If an app fails

The result card says why. Common fixes:

| Message | Fix |
| --- | --- |
| "Couldn't find the message box" | Open that app yourself, finish sign-in / welcome screens, try again |
| "didn't open" | Android blocked the launch; try again with AskAll in the foreground |
| "No reply detected" | Slow network or a long answer; try again, or raise `MAX_WAIT_MS` |
| Replies cut short | Raise `STABLE_MS` in `ChatDriver.kt` (the app is pausing mid-answer) |

All timing numbers live at the top of `ChatDriver.kt`. Known apps are listed in `AiApps.kt`.
