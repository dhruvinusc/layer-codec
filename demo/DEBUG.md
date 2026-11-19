# Demo debug: symptom, cause, fix

| Symptom | Likely cause | Fix |
|---|---|---|
| Playback stutters or shows late frames | First run after a build, or the Mac is busy | Run menu 1 (warm-up). Close other apps. Plug in. |
| `error: file not found: data/..._rle.cmp` or `_mc.cmp` | Pre-encode never ran | Menu 0. |
| `STOP` with `disk: under 15 GB` | Low disk | Empty Trash or `~/Library/Caches`. Or `DISK_OK=1 bash demo/demo.sh` (the 3 files need about 0.2 GB). |
| `error: ... not a .cmp file` or usage text | Wrong argument (for example the .mp4 first) | `./mydecoder data/video2.cmp data/video2.mp4`: .cmp first, mp4 second. |
| `ffmpeg: MISSING` | conda not active | `conda activate base`. |
| `jar: MISSING` | No build | `./gradlew build`. |
| `UnsupportedClassVersionError` | Old Java picked up | Install or select Java 21 (`/usr/libexec/java_home -v 21`). |
| `s` prints "no original was given" | Started with menu 3 (no mp4) | Use menu 4. |
| Arrow keys print `^[[D` in Terminal | Terminal had focus, not the player window | Click the player window first. |
| Typed a menu number at a `[y/N]` question | Item was still asking | Press Enter, then type the number again. |
| Item 5 says no `SAL_52.cmp` | (5,2) file never made | `ln -sf SAL.mp4 data/SAL_52.mp4 && ./myencoder data/SAL_52.mp4 5 2` (about 130 s, about 1.1 GB). Or show `docs/img/p3r_SAL_swap_crop_x3.png`. |
| Mac sleeps mid-demo | caffeinate not running | `caffeinate -dims &`. |
