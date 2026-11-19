# Changelog

Downloads for each version are on the [releases page](https://github.com/raghavsunil99/smart-teleprompter/releases).

## 1.9
- The red reading band now sits in the middle of the screen by default (it was 30% from the top). Still adjustable in Settings. If you hadn't changed it, it moves to the middle automatically.

## 1.8
- Scrolling no longer stalls when the recognizer writes words it can't match to your script. It counts the words you're saying and keeps moving, up to a few lines, and snaps back onto the script as soon as a word matches again.

## 1.7
- English words inside Telugu, Malayalam or Tamil scripts follow smoothly, even when the recognizer writes them in its own letters. In a simulated reading of a real mixed script, words that failed to move the position went from 37 to 0.
- Joined words like `useState` are followed as two words, as they're said.
- Numbers match how they're said: 5 = five = ఐదు.
- Scrolling keeps going while you're talking, even through a word it can't match.

## 1.6
- **Telugu, Malayalam, Tamil and English.** Each script listens in its own language: worked out from its letters, or chosen in the editor, in Settings or from the laptop.
- Malayalam and Tamil scripts follow you whether they're written in their own letters or typed in English letters.
- Laptop remote: a Language switch, and the laptop mic listens in the script's language.
- Sample scripts in all four languages.

## 1.5
- New name, **Smart Teleprompter by Raghav**, and a new icon.
- First public release.

## 1.4
- **Cruise-control scrolling**: moves at your speaking pace instead of jumping with each burst of recognized words, with sub-pixel motion. About 7× smoother in testing.
- Gliding word highlight (or whole line, or off).
- Brightness is left alone unless you choose Maximum or Custom.

## 1.3
- No more jumping into other sections when a script repeats words: farther matches need more evidence, and only newly heard words can move the position.
- Laptop remote rebuilt: live script you can click to jump, laptop mic level meter, scripts list and editor, one fast WebSocket connection, low-latency Wi-Fi.

## 1.2
- Full-screen prompter with one slim, labelled control bar.
- Softer reading band; the reading line settles in the band when you pause.

## 1.1
- Continuous listening on Android 13+.
- Retakes, `# sections`, 3-2-1 countdown, resume where you left off.
- Bluetooth remotes, foot pedals and keyboards.

## 1.0
- Voice-following teleprompter for Telugu, with teleprompter (mirror) mode and a laptop remote with laptop mic.
