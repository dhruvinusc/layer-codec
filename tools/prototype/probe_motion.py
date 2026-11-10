"""Quick feasibility probe (prototype only, not project code).
For sample frame pairs: full-search SAD block matching on Y (16x16, R=16),
then compare how well a pure translation vs an affine camera model explains the field.
Also estimates .cmp size for raw int16 vs zlib."""
import sys, zlib, time
import numpy as np, cv2

B, R = 16, 16

def read_pair(path, idx):
    cap = cv2.VideoCapture(path)
    cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
    ok1, f1 = cap.read(); ok2, f2 = cap.read()
    return f1, f2

def y_plane(bgr):
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2YCrCb)[:, :, 0].astype(np.int32)

def full_search(prev, cur):
    H, W = cur.shape
    H16, W16 = H // B * B, W // B * B
    cur = cur[:H16, :W16]
    pad = np.pad(prev, R, mode='edge')
    nby, nbx = H16 // B, W16 // B
    best = np.full((nby, nbx), np.iinfo(np.int64).max, np.int64)
    mv = np.zeros((nby, nbx, 2), np.int32)
    for dy in range(-R, R + 1):
        for dx in range(-R, R + 1):
            ref = pad[R + dy:R + dy + H16, R + dx:R + dx + W16]
            sad = np.abs(cur - ref).reshape(nby, B, nbx, B).sum(axis=(1, 3))
            # slight zero bias
            if dx == 0 and dy == 0:
                sad = sad - 64
            m = sad < best
            best[m] = sad[m]; mv[m] = (dx, dy)
    # texture (variance) per block for reliability
    var = cur.reshape(nby, B, nbx, B).astype(np.float64).var(axis=(1, 3))
    return mv, var

def model_fit(mv, var):
    nby, nbx, _ = mv.shape
    ys, xs = np.mgrid[0:nby, 0:nbx]
    cx = (xs * B + B / 2).ravel().astype(np.float32)
    cy = (ys * B + B / 2).ravel().astype(np.float32)
    v = mv.reshape(-1, 2).astype(np.float32)
    rel = var.ravel() > 50
    src = np.stack([cx, cy], 1)[rel]; dst = src + v[rel]
    # translation = mode
    keys, counts = np.unique(v[rel], axis=0, return_counts=True)
    mode = keys[counts.argmax()]
    tr_in = (np.abs(v[rel] - mode).max(1) <= 1).mean()
    A, inl = cv2.estimateAffine2D(src, dst, ransacReprojThreshold=1.5)
    aff_in = inl.mean() if inl is not None else 0
    return mode, tr_in, aff_in, rel.mean(), A

def size_est(bgr, n_fg=2, n_bg=5, fg_frac=0.25):
    H, W, _ = bgr.shape
    H16 = (H + 15) // 16 * 16; W16 = (W + 15) // 16 * 16
    img = cv2.copyMakeBorder(bgr, 0, H16 - H, 0, W16 - W, cv2.BORDER_REPLICATE).astype(np.float32) - 128
    out = []
    nb = (H16 // 8) * (W16 // 8)
    rng = np.random.default_rng(0)
    for c in range(3):
        ch = img[:, :, c]
        for by in range(0, H16, 8):
            for bx in range(0, W16, 8):
                d = cv2.dct(ch[by:by + 8, bx:bx + 8])
                n = n_fg if rng.random() < fg_frac else n_bg
                out.append(np.round(d / (1 << n)).astype(np.int16))
    arr = np.stack(out)
    raw = arr.nbytes + nb
    z = len(zlib.compress(arr.tobytes(), 1))
    return raw, z, (arr == 0).mean(), arr.min(), arr.max()

if __name__ == '__main__':
    for path in sys.argv[1:]:
        cap = cv2.VideoCapture(path); N = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)); cap.release()
        print('=====', path.split('/')[-1], 'frames', N)
        for idx in (N // 5, N // 2, 4 * N // 5):
            f1, f2 = read_pair(path, idx)
            t = time.time()
            mv, var = full_search(y_plane(f1), y_plane(f2))
            dt = time.time() - t
            mode, tr_in, aff_in, relfrac, A = model_fit(mv, var)
            print(f' frame {idx}: fullsearch {dt:.1f}s  mode MV {mode}  inliers translation {tr_in:.2f}  affine {aff_in:.2f}  textured {relfrac:.2f}')
            if A is not None:
                print('   affine scale/rot terms', np.round(A[:, :2] - np.eye(2), 4).ravel(), 'shift', np.round(A[:, 2], 1))
        raw, z, zf, lo, hi = size_est(f2)
        print(f' size/frame raw {raw/1e6:.2f} MB, zlib {z/1e6:.2f} MB, zeros {zf:.2f}, coeff range {lo}..{hi};'
              f' whole clip raw {raw*N/1e9:.2f} GB zlib {z*N/1e9:.2f} GB')
