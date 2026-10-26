# Music2NES

Turn any song into a playable NES ROM, right on your Android phone.

[![Download APK](https://img.shields.io/badge/Download-Music2NES.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Trevor792221/MusicToNES/releases/latest/download/Music2NES.apk)

Pick an audio file, check the artist and title text, and tap **Convert**. You get a 512 KB `.nes` ROM that:

- shows the artist and title on screen
- plays the whole song through the NES's sample channel (`$4011`)
- uses **A** to play and pause, and rewinds and pauses when the song ends

## Install

1. Tap the **Download APK** button above on your phone.
2. Open the downloaded file and allow installs from unknown sources if asked.
3. Requires Android 5.0 or newer.

## How it works

- **Decoding:** Android's built-in decoders, so anything your phone can play works (mp3, m4a, opus, ogg, flac, wav, video soundtracks).
- **Encoding:** the audio is resampled to fit, then stored as 4-bit delta codes (two samples per byte). A Viterbi search picks the codes so the 7-bit output follows the original as closely as possible, with the DAC's non-linearity corrected.
- **ROM:** mapper 2 (UxROM), 512 KB, CHR-RAM. A cycle-counted 6502 loop writes every sample to the DAC at a steady rate.
- **Sound quality:** a 4-minute song plays at about 4.2 kHz; shorter songs get a higher rate. Songs up to about 22 minutes fit.

Some emulators only read 4 bits of the UxROM bank number, so they loop the song after about 30 seconds. Mesen, FCEUX and most others play it all the way through.

## Building

The APK is built with the plain Android SDK tools, without Gradle:

```sh
ANDROID_HOME=/path/to/android-sdk ./build_apk.sh
```

This needs build-tools 35.0.0, platform android-35 and a JDK.

`tools/build.py` is the desktop version of the converter (Python, needs ffmpeg and numpy):

```sh
python3 tools/build.py song.mp3 song.nes 512    # or 1024 for a 1 MB ROM
```

`tools/emu.py` is a py65-based test that checks the ROM's sample timing, pause and resume.
