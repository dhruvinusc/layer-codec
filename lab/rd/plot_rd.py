#!/usr/bin/env python3
"""Lab L3 charts (static PNG for slides and report). Usage, from the repo root:
    python3 lab/rd/plot_rd.py [rd_csv] [--tag mac|sandbox]
Reads the RdSweep CSV (default lab/out/rd/rd_video2.csv) and writes into lab/out/rd/:
  rd_<clip>_fixed_n1.png        n1 = 2 kept, n2 = 0..7: foreground and background PSNR vs file size, 3 modes
  rd_<clip>_even_vs_uneven.png  foreground PSNR vs size: even (n, n) against uneven (1, n2) and (2, n2), per mode
  sizes_4clips.png              mp4 vs baseline vs mode 1 vs mode 2, all 4 clips (RESULTS "Final Mac check")
Palette: dataviz reference palette, categorical slots 1-3, validated (adjacent CVD dE 9.2, normal 27.6);
aqua is under 3:1 contrast, so every series is also direct-labelled and has its own marker.
"""
import csv, os, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

SURFACE, INK, INK2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a"]          # slot 1 blue, 2 orange, 3 aqua (fixed order)
MARK = ["o", "s", "^"]
MODE = ["Baseline (mode 0)", "Zigzag + RLE (mode 1)", "Motion-comp GOP 2 (mode 2)"]
OUT = "lab/out/rd"

plt.rcParams.update({"font.size": 12, "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2,
                     "ytick.color": INK2, "axes.spines.top": False, "axes.spines.right": False,
                     "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE})

def load(path):
    rows = list(csv.DictReader(open(path)))
    d = {}
    for r in rows:
        d[(int(r["n1"]), int(r["n2"]), int(r["mode"]))] = r
    return rows[0]["clip"], int(rows[0]["frames"]), d

def style(ax, ylabel):
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.set_xlabel("File size, MB (log scale)")
    ax.set_ylabel(ylabel)
    ax.set_xscale("log")

def line(ax, xs, ys, i, label, ls="-", pts=None):
    ax.plot(xs, ys, ls, color=SERIES[i], linewidth=2, marker=MARK[i], markersize=8,
            markeredgecolor=SURFACE, markeredgewidth=1.5, label=label)
    if pts:
        for x, y, t in pts:
            ax.annotate(t, (x, y), textcoords="offset points", xytext=(0, 9), ha="center", fontsize=9, color=INK2)

def fixed_n1(clip, frames, d, tag, n1=2):
    fig, axes = plt.subplots(1, 2, figsize=(14, 5.6))
    n2s = [n for n in range(8) if (n1, n, 0) in d]
    for ax, key, title in [(axes[0], "psnr_fg", "Foreground PSNR (n1 = %d, fixed)" % n1),
                           (axes[1], "psnr_bg", "Background PSNR (n2 = 0..7)")]:
        for m in range(3):
            xs = [float(d[(n1, n, m)]["file_mb"]) for n in n2s]
            ys = [float(d[(n1, n, m)][key]) for n in n2s]
            pts = [(x, y, "n2=%d" % n) for x, y, n in zip(xs, ys, n2s)] if (m == 0 and key == "psnr_bg") else None
            line(ax, xs, ys, m, MODE[m], pts=pts)
            if key == "psnr_bg":  # direct labels at the n2 = 7 end, nudged apart (lines meet there)
                off = [(12, -4, "left"), (0, -20, "center"), (-4, 10, "right")][m]
                ax.annotate(MODE[m].split(" (")[0], (xs[-1], ys[-1]), textcoords="offset points",
                            xytext=off[:2], ha=off[2], fontsize=10, color=INK)
        if key == "psnr_fg":
            ys_all = [float(d[(n1, n, m)][key]) for n in n2s for m in range(3)]
            ax.annotate("All three modes stay at %.1f to %.1f dB for every n2:\nthe foreground is untouched" %
                        (min(ys_all), max(ys_all)), (0.03, 0.80), xycoords="axes fraction", fontsize=11, color=INK)
        style(ax, "PSNR, dB")
        ax.set_title(title, loc="left", color=INK, fontsize=13, fontweight="bold")
    axes[1].set_xlim(left=axes[1].get_xlim()[0] * 0.85)
    axes[0].set_xlim(axes[1].get_xlim())
    axes[1].set_ylim(bottom=axes[1].get_ylim()[0] - 3)
    axes[0].set_ylim(axes[1].get_ylim())
    axes[0].legend(frameon=False, loc="lower right")
    fig.suptitle("%s, first %d frames: raising n2 shrinks the file and only the background pays" % (clip, frames),
                 x=0.01, ha="left", fontsize=15, fontweight="bold", color=INK)
    fig.text(0.01, 0.005, "Each point is one full encode (exact .cmp bytes, mean per-frame PSNR). Mode 1 is lossless "
             "re-packing of mode 0, so its PSNR equals mode 0. Source: lab.rd.RdSweep (%s)." % tag, fontsize=9, color=INK2)
    fig.tight_layout(rect=(0, 0.03, 1, 0.94))
    p = os.path.join(OUT, "rd_%s_fixed_n1.png" % clip); fig.savefig(p, dpi=150); print("wrote", p)

def even_vs_uneven(clip, frames, d, tag):
    fig, axes = plt.subplots(1, 3, figsize=(16, 5.4), sharey=True)
    curves = [("Even split (n, n)", lambda n: (n, n)), ("Uneven, n1 = 1", lambda n: (1, n)),
              ("Uneven, n1 = 2", lambda n: (2, n))]
    for m, ax in enumerate(axes):
        for i, (name, pair) in enumerate(curves):
            ns = [n for n in range(8) if pair(n)[1] >= pair(n)[0] and (*pair(n), m) in d]
            xs = [float(d[(*pair(n), m)]["file_mb"]) for n in ns]
            ys = [float(d[(*pair(n), m)]["psnr_fg"]) for n in ns]
            line(ax, xs, ys, i, name, pts=[(x, y, str(n)) for x, y, n in zip(xs, ys, ns)] if i == 0 else None)
            if i == 0:
                k = ns.index(4)
                ax.annotate(name, (xs[k], ys[k]), textcoords="offset points", xytext=(14, -14), fontsize=10, color=INK)
            else:
                ax.annotate(name, (xs[-1], ys[-1]), textcoords="offset points", xytext=(4, -16), fontsize=10, color=INK)
        style(ax, "Foreground PSNR, dB" if m == 0 else "")
        ax.set_title(MODE[m], loc="left", color=INK, fontsize=13, fontweight="bold")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, frameon=False, loc="upper right", ncol=3, bbox_to_anchor=(0.995, 0.95))
    fig.suptitle("%s: at the same file size, the uneven split keeps the moving subject far sharper" % clip,
                 x=0.01, ha="left", fontsize=15, fontweight="bold", color=INK)
    fig.text(0.01, 0.005, "Even: both layers use n (labels on the blue points). Uneven: n1 fixed, n2 = n1..7 "
             "(background gets coarser to the left). First %d frames; source lab.rd.RdSweep (%s)." % (frames, tag),
             fontsize=9, color=INK2)
    fig.tight_layout(rect=(0, 0.03, 1, 0.88))
    p = os.path.join(OUT, "rd_%s_even_vs_uneven.png" % clip); fig.savefig(p, dpi=150); print("wrote", p)

def sizes():
    # RESULTS.md "Final Mac check" (commit 88fc181, (n1, n2) = (2, 5), whole clips); mp4 = data/*.mp4 incl. AAC audio.
    clips = [("video1", 3.64, 65.0, 38.7, 32.4), ("video2", 2.73, 85.4, 60.9, 54.5),
             ("Stairs", 21.90, 291.0, 193.9, 151.1), ("SAL", 27.75, 424.7, 269.0, 207.3)]
    names = ["Source mp4 (H.264)", "Baseline", "Zigzag + RLE", "Motion-comp"]
    colors = ["#9b9a95"] + SERIES
    fig, axes = plt.subplots(1, 4, figsize=(16, 5.2))
    for ax, (clip, *v) in zip(axes, clips):
        bars = ax.bar(range(4), v, color=colors, width=0.72, edgecolor=SURFACE, linewidth=2)
        for b, val in zip(bars, v):
            ax.annotate("%.1f" % val, (b.get_x() + b.get_width() / 2, val), textcoords="offset points",
                        xytext=(0, 4), ha="center", fontsize=10, color=INK)
        ax.set_xticks(range(4)); ax.set_xticklabels(["mp4", "mode 0", "mode 1", "mode 2"])
        ax.set_title(clip, loc="left", fontsize=13, fontweight="bold", color=INK)
        ax.grid(True, axis="y", color=GRID); ax.set_axisbelow(True)
        ax.set_ylim(0, max(v) * 1.15)
        ax.annotate("mode 2 is %.0fx the mp4" % (v[3] / v[0]), (0.98, 0.97), xycoords="axes fraction",
                    ha="right", va="top", fontsize=10, color=INK2)
    axes[0].set_ylabel("File size, MB (each clip has its own scale)")
    fig.legend(bars, names, frameon=False, loc="upper right", ncol=4, bbox_to_anchor=(0.995, 0.955))
    fig.suptitle("File size at (2,5), whole clips: mode 2 is 49 to 64% of baseline; H.264 is still 7 to 20x smaller",
                 x=0.01, ha="left", fontsize=15, fontweight="bold", color=INK)
    fig.text(0.01, 0.005, "Source: docs/RESULTS.md 'Final Mac check' (88fc181). mp4 sizes include AAC audio. "
             "Our codec has no entropy coder and (mode 0/1) no prediction, so it cannot match H.264; the point is the "
             "per-layer control.", fontsize=9, color=INK2)
    fig.tight_layout(rect=(0, 0.03, 1, 0.88))
    p = os.path.join(OUT, "sizes_4clips.png"); fig.savefig(p, dpi=150); print("wrote", p)

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    tag = sys.argv[sys.argv.index("--tag") + 1] if "--tag" in sys.argv else "mac"
    args = [a for a in args if a != tag]
    os.makedirs(OUT, exist_ok=True)
    clip, frames, d = load(args[0] if args else os.path.join(OUT, "rd_video2.csv"))
    fixed_n1(clip, frames, d, tag)
    even_vs_uneven(clip, frames, d, tag)
    sizes()
