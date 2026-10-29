# Music2NES

Turn any song into a playable NES ROM, right on your Android phone.

[![Download APK](https://img.shields.io/badge/Download-Music2NES.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Trevor792221/MusicToNES/releases/latest/download/Music2NES.apk)

Pick an audio file, check the artist and title text, and tap **Convert to 512 KB ROM** or **Convert to 1 MB ROM**. The `.nes` ROM you get:

- shows the artist and title, plus an elapsed / total timer and a progress bar at the bottom of the screen
- plays the whole song through the NES's sample channel (`$4011`)
- uses **A** to play and pause, and rewinds and pauses when the song ends

## Install

1. Tap the **Download APK** button above on your phone.
2. Open the downloaded file and allow installs from unknown sources if asked.
3. Requires Android 5.0 or newer.

## Sound quality

The sample rate is chosen to fit the song into the ROM size:

| Song length | 512 KB | 1 MB |
|---|---|---|
| 2 minutes | ~8.2 kHz | ~8.5 kHz |
| 4 minutes | ~4.1 kHz | ~8.3 kHz |
| 8 minutes | ~2.1 kHz | ~4.1 kHz |

The maximum is about 8.5 kHz. The longest song that fits is about 14 minutes in 512 KB and 29 minutes in 1 MB, at a very low sample rate.

Some emulators only read 4 bits of the UxROM bank number, so they loop the song after about 30 seconds. Mesen, FCEUX and most others play it all the way through.

## How it works

- **Decoding:** Android's built-in decoders, so anything your phone can play works (mp3, m4a, opus, ogg, flac, wav, video soundtracks).
- **Encoding:** the audio is resampled to fit, then stored as 4-bit delta codes (two samples per byte). A Viterbi search picks the codes so the 7-bit output follows the original as closely as possible, with the DAC's non-linearity corrected.
- **ROM:** mapper 2 (UxROM), CHR-RAM. A cycle-counted 6502 loop writes every sample to the DAC at a steady rate.
- **Screen updates without interrupts:** every sample slot runs the same fixed-length block of code. It polls for vertical blank and makes a few PPU writes from a precomputed list, so the timer and bar update during vertical blank without disturbing the sample timing. The timer digits and bar position for each 256-byte page of audio are precomputed and stored in the last two pages of each 16 KB bank.

## Building

The APK is built with the plain Android SDK tools, without Gradle:

```sh
ANDROID_HOME=/path/to/android-sdk ./build_apk.sh
```

This needs build-tools 35.0.0, platform android-35 and a JDK.

`tools/convert.sh` runs the same converter on a desktop (needs ffmpeg and a JDK):

```sh
tools/convert.sh song.mp3 song.nes 1024 "Artist" "Title"    # or 512
```

`tools/emu.py` checks a ROM in a py65 6502 emulator. It tests sample timing, the decoded audio, that every PPU write lands in vertical blank, the on-screen timer and bar, pause/resume, and the end-of-song rewind:

```sh
pip install py65 numpy
python3 tools/emu.py song.nes
```

## Releasing

Bump `VERSION`, rebuild, copy `Music2NES.apk` into `dist/` and push to `main`. The Release workflow publishes it as `v<VERSION>`, and the download button always points at the latest release.
