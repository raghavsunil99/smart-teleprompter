<p align="center">
  <img src="docs/images/hero.png" alt="Smart Teleprompter by Raghav: a teleprompter that listens and scrolls as you speak" width="100%">
</p>

<p align="center">
  <a href="https://github.com/raghavsunil99/smart-teleprompter/releases/latest"><img src="https://img.shields.io/github/v/release/raghavsunil99/smart-teleprompter?label=download&color=ffc94d&style=for-the-badge" alt="Download"></a>
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=fff&style=for-the-badge" alt="Android 8+">
  <img src="https://img.shields.io/badge/Laptop%20remote-Chrome-4285F4?logo=googlechrome&logoColor=fff&style=for-the-badge" alt="Laptop remote in Chrome">
</p>

<p align="center">
  <b>Smart Teleprompter listens while you talk and scrolls your script along with you.</b><br>
  Made for Telugu creators: scripts in Telugu letters, in Tenglish (Telugu typed in English letters), or in English.
</p>

<p align="center">
  <a href="#-download">Download</a> ·
  <a href="#-features">Features</a> ·
  <a href="#-laptop-remote">Laptop remote</a> ·
  <a href="#-in-a-teleprompter-rig">Teleprompter rig</a> ·
  <a href="#build-from-source">Build from source</a>
</p>

<p align="center">
  <img src="docs/images/reading.png" alt="Reading a Tenglish script: the current word is highlighted in the red reading band, words already read fade to grey" width="100%">
</p>

## ⬇ Download

| Device | File | Notes |
|---|---|---|
| 🤖 **Android** 8+ phone or tablet | [Smart-Teleprompter-by-Raghav-1.5.apk](https://github.com/raghavsunil99/smart-teleprompter/releases/latest) | Allow installing from your browser or Files app, then **Install anyway** |
| 💻 **Laptop remote** (optional) | Nothing to install | Open the address the app shows in **Chrome** on a laptop on the same Wi-Fi |

Step-by-step instructions: [HOW TO INSTALL SMART TELEPROMPTER.txt](docs/HOW%20TO%20INSTALL%20SMART%20TELEPROMPTER.txt)

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### 🎙 Voice Follow
Start talking and the script moves with you. It listens in Telugu, works out which word you're on, and scrolls at your own pace like cruise control: steady while you speak, gliding to a stop when you pause.

</td>
<td width="50%" valign="top">

### ⇅ Built for teleprompter glass
One tap flips the text for a mirror rig and locks landscape. While you record, the screen is just your script on black, with a soft red band on the line to read.

</td>
</tr>
<tr>
<td><img src="docs/images/controls.png" alt="The prompter with its control bar: Steady or Voice Follow, play, text size, Prompter mode, Remote and Settings"></td>
<td><img src="docs/images/teleprompter.png" alt="Teleprompter mode: the script flipped upside-down for the glass, with the reading band"></td>
</tr>
<tr>
<td width="50%" valign="top">

### 🔤 Telugu, Tenglish and English
Write your script however you like. *namaskaram*, *namaskaaram* and నమస్కారం all count as the same word, and English words like *video* or *subscribe* match even when they're heard as Telugu.

</td>
<td width="50%" valign="top">

### 🔁 Retakes and sections
Start a sentence again and the script jumps back to it. Mark parts with `# Intro`, `# Tip 1` and jump between them. Each script remembers where you stopped.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 🎮 Remotes and pedals
Bluetooth clickers, foot pedals, keyboards and camera-shutter remotes work out of the box: play/pause, next and previous line, back to start.

</td>
<td width="50%" valign="top">

### ⏱ The details
A 3-2-1 countdown, a gliding word highlight (or whole line, or none), Steady mode at a fixed speed, adjustable reading band, text size and brightness.

</td>
</tr>
</table>

## 💻 Laptop remote

Turn on **Remote** in the app and open the address it shows in Chrome on your laptop. No PIN by default; switch on **Require PIN** on public Wi-Fi.

- **Live script**: see exactly where the phone is, and click any word to make it continue from there.
- **Laptop mic**: if the phone is a couple of metres away in a rig, let the laptop in front of you do the listening. The phone follows it, and switches back to its own mic if the laptop stops.
- **Control everything**: play, mode, lines, sections, text size, speed, flip, with keyboard shortcuts.
- **Write scripts on the laptop** and send them to the phone.

<p align="center">
  <img src="docs/images/remote.png" alt="The laptop remote: the live script with the current word highlighted, controls, sections and the script list" width="100%">
</p>

> The laptop mic needs the **https** address. The first time, Chrome says "Your connection is not private": click **Advanced → Proceed**. It's your own phone on your own Wi-Fi.

## 🎬 In a teleprompter rig

1. Put the phone in the rig and tap **Prompter** (flips the text and locks landscape).
2. Record on another phone or camera behind the glass.
3. Press ▶ on the phone, a remote, or the laptop, and start talking after the countdown.

If the phone is far from you, use the **laptop mic** (above). If you lose your place, long-press a word on the phone or click it on the laptop.

---

## Build from source

The rest of this page is for developers.

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

## How it works

- **Listening**: Android's speech recognizer in Telugu (`te-IN`), with partial results, kept running across pauses (one segmented session on Android 13+). The laptop remote uses Chrome's Web Speech API and sends what it hears over a WebSocket.
- **Matching** (`ScriptMatcher`): the last few heard words are aligned to the script near the current position. Telugu letters and Tenglish are both turned into the same Latin sound key (`Phonetic`). The further ahead a match is, the more (and the more contiguous) matching words it needs, and only newly heard words can move the position, so repeated phrases in other sections don't pull it away. A restarted sentence is a retake.
- **Scrolling** (`CruiseFollower`): scrolls at the speaker's measured pace in pixels per second and corrects drift over about two seconds, rather than chasing each burst of recognized words. Drawn with sub-pixel offsets.
- **Remote** (`RemoteServer`, `RemoteHub`): a small web server on the phone, over http and https, serving the remote page plus a WebSocket for commands, heard words and live status.

## Layout

- `app/src/main/java/com/telugu/prompter/`
  - `PrompterScreen.kt`: the prompter: scrolling, highlight, controls, settings
  - `ScriptMatcher.kt`, `Phonetic.kt`: following the speaker through the script
  - `CruiseFollower.kt`: the voice-follow scroll; `ScrollFollower.kt` is the older word-based one, kept as the smoothness baseline in tests
  - `SpeechFollower.kt`: Android speech recognition
  - `RemoteServer.kt`, `RemoteHub.kt`: laptop remote server and the Remote dialog
  - `RemoteKeys.kt`: Bluetooth remotes, pedals and keyboards
  - `ScriptStore.kt`, `ScriptScreens.kt`, `MainActivity.kt`: scripts, settings, list and editor
- `app/src/main/assets/remote.html`: the laptop remote page
- `app/src/test/`: matcher, Tenglish, retake, smoothness, remote server and key tests

## Notes

- Telugu speech recognition comes from the phone's Google speech service (or Chrome on the laptop), so it usually needs internet.
- The https address uses a self-signed certificate bundled in the app, which is why Chrome asks you to proceed the first time.
- Android shows its green microphone indicator while the phone's mic is listening; apps can't hide it.
