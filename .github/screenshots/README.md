# README screenshots

The release workflow builds the Jellyfin server from the pinned [lyrics PR](https://github.com/jellyfin/jellyfin/pull/17011), indexes the demo music, and captures Bell using the signed release APK on an Android emulator. It attaches the PNGs to the release and refreshes the images on the repository home page.

To reproduce the capture, start a disposable local Jellyfin instance from the same revision. Give it separate config, data, and cache directories and bind it to `127.0.0.1:18096`. With Node.js 24, FFmpeg, curl, ADB, and Maestro CLI 2.12.1 on your PATH, run the commands below with a disposable emulator.

```sh
node .github/screenshots/prepare-library.mjs dist/screenshots/music http://127.0.0.1:18096
adb -s emulator-5554 reverse tcp:18096 tcp:18096
adb -s emulator-5554 install /path/to/bell-release.apk
adb -s emulator-5554 shell wm size 1080x2400
adb -s emulator-5554 shell wm density 392
maestro --device emulator-5554 test -e BELL_SERVER=http://127.0.0.1:18096 --test-output-dir dist/screenshots/run .github/screenshots/phone.yaml
adb -s emulator-5554 shell wm size 1000x1600
adb -s emulator-5554 shell wm density 200
maestro --device emulator-5554 test --test-output-dir dist/screenshots/run .github/screenshots/tablet.yaml
```

Maestro clears Bell's data when starting the phone flow. The tablet flow uses the same session and playback state. Images are saved in each flow's 	akeScreenshot artifact directory.

The demo uses eight recordings sourced from Wikimedia Commons and the Library of Congress National Jukebox. The fixture lists and verifies each asset by checksum. Album covers are generated for the demo. The word timings for the 1909 Ada Jones and Billy Murray recording of “Shine On, Harvest Moon” are approximate and cover the sung sections. The lyric text is based on the [1908 sheet music](https://scholarsjunction.msstate.edu/cht-sheet-music/13099/).
