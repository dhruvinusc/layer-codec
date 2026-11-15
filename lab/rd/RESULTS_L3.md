# L3 results (Bits and Budgets)

Sizes and PSNR do not depend on the machine (only FFmpeg's decoded pixels can differ slightly, RESULTS W2); speed is not reported here. Sandbox numbers below; Mac numbers come from `bash lab/rd/mac_rd.sh` and replace them.

## Check: in-memory core equals the real encoders (sandbox, Nov 15)
| Check | Real tool | lab.rd | Match |
|---|---|---|---|
| video2 first 20 fr, (2,5), mode 2 | McEncode: 3,102,696 B | 3,102,696 B (CSV 3.1027 MB) | exact |
| video2 whole, (2,5), mode 0 | RESULTS P4 final 86.7 MB, 34.29 / 47.78 / 33.99 | 86.70 MB, 34.29 / 47.78 / 33.99 | exact |
| same, mode 1 | 61.6 MB | 61.59 MB | exact |
| same, mode 2 | 55.1 MB, 34.07 / 47.55 / 33.76 | 55.07 MB, 34.07 / 47.55 / 33.76 | exact |

## RD sweep, video2 first 120 frames (sandbox, `lab/out/rd/rd_video2_sandbox.csv`)
n1 = 2 kept, n2 0 to 7: foreground stays 47.5 to 47.7 dB in every mode while the file falls from 107.8 MB to 14.1 MB (baseline) or 7.8 MB (mode 2), and background PSNR falls from 59.5 to 26.7 dB.
Even split vs uneven at about the same size (baseline): (4,4) is 32.1 MB with fg 38.6 dB; (2,6) is 19.0 MB with fg 47.7 dB. Uneven gives the subject 9.2 dB more for 41% fewer bytes; the price is the background (30.3 vs 38.8 dB).

## Rate control (n1 = 2 kept, sandbox)
| Clip, budget | Baseline | Zigzag + RLE | Motion-comp GOP 2 |
|---|---|---|---|
| video2, 20 Mbps | n2 9: 18.6 Mbps, fg 47.75 / bg 19.93 | n2 7: 17.9, 47.75 / 26.44 | n2 7: 16.1, 47.53 / 26.33 |
| SAL, 100 Mbps | n2 8: 62.5 Mbps, fg 47.63 / bg 23.16 | n2 6: 87.4, 47.63 / 30.20 | n2 6: 64.0, 47.41 / 30.13 |
Same budget, same face quality; the better mode spends the saving on the background (SAL: bg 23 dB -> 30 dB). At n2 = 10 on video2, mode 2 (10.85 Mbps) is slightly bigger than mode 1 (10.56): once residuals are nearly all zero, the motion vectors cost more than prediction saves.

## Note for Q&A
SAL frame 218: the largest "foreground" region is the yellow banner moving in the wind (83 macroblocks; walker 67). The segmenter labels motion, not people, exactly as the brief asks (B9). `dct_SAL_f218.png` shows it; the teaching figure uses frame 150 (walker = largest region, 101 macroblocks).
