#!/usr/bin/env python3
"""アプリで描いた取り札を、デザインの参照画像と数値で比べる。

使い方:
  ./gradlew testDebugUnitTest --tests '*TorifudaReferenceScreenshotTest'
  python3 tools/compare_torifuda.py

design/card/torifuda_reference.png の2枚（左が78番、右が58番）と、同じ札をアプリで同じ大きさに描いた
app/build/outputs/roborazzi/torifuda_reference_78.png・_58.png（TorifudaReferenceScreenshotTest）を比べる。
比べる項目: 縁の太さ（四辺）と色、地の色、各字のインクの外接矩形（位置と大きさ）。
位置は札の外形の左上を原点にした px（アプリの札は参照画像の大きさにそろえる）。字の位置ずれの最大値は
「の」「で」を除いて出す（参照画像ではこの2字だけ作者が目で見て下げてあり、アプリでは再現しない）。
字の大きさは、この2字も含めてすべての字で比べる。
目標（縁の太さ ±1px、字の位置と大きさ ±2px）を外れたら終了コード 1 を返す。
"""
import argparse
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
REFERENCE = ROOT / "design" / "card" / "torifuda_reference.png"
SHOTS = ROOT / "app" / "build" / "outputs" / "roborazzi"

# 参照画像の札（左から）の番号と下の句（空白を除く）
CARDS = [
    ("78", "いくよねざめぬすまのせきもり"),
    ("58", "いでそよひとをわすれやはする"),
]
# 参照画像で作者が目で見て位置を直した字（位置の判定だけから除く）
HAND_ADJUSTED = set("ので")
EDGE_TOLERANCE = 1.0
GLYPH_TOLERANCE = 2.0

LUMA = np.array([0.299, 0.587, 0.114])
SIDES = ("左", "右", "上", "下")


def load_rgb(path: pathlib.Path) -> np.ndarray:
    """白地に合成した RGB（float）。"""
    rgba = np.asarray(Image.open(path).convert("RGBA")).astype(float)
    alpha = rgba[..., 3:4] / 255.0
    return rgba[..., :3] * alpha + 255.0 * (1.0 - alpha)


def runs(indices: np.ndarray, min_gap: int) -> list[tuple[int, int]]:
    """昇順の添字を、min_gap より広くあいたところで区切った範囲（両端を含む）。"""
    out = []
    start = prev = int(indices[0])
    for i in indices[1:]:
        if i - prev > min_gap:
            out.append((start, prev))
            start = int(i)
        prev = int(i)
    out.append((start, prev))
    return out


def find_cards(rgb: np.ndarray) -> list[tuple[int, int, int, int]]:
    """札（濃い縁で囲まれた範囲）を左から順に探す。画素単位の (左, 上, 右, 下)。右と下は含まない。"""
    mask = rgb @ LUMA < 200
    cards = []
    for x0, x1 in runs(np.where(mask.any(axis=0))[0], min_gap=8):
        ys = np.where(mask[:, x0:x1 + 1].any(axis=1))[0]
        cards.append((x0, int(ys[0]), x1 + 1, int(ys[-1]) + 1))
    return cards


def edge_position(profile: np.ndarray, start: float, end: float) -> float:
    """外から内へ並べた輝度 profile が start から end へ変わる位置（profile の先頭の画素の端を 0 とする px）。

    最初に end になった画素の位置から、その手前の境目の画素の被覆率を引く。
    """
    cov = np.clip((profile - start) / (end - start), 0.0, 1.0)
    full = int(np.argmax(cov >= 0.98))
    pos = float(full)
    k = full
    while k > 0 and cov[k - 1] > 0.02:
        k -= 1
        pos -= cov[k]
    return pos


def crossing(profile: np.ndarray) -> float:
    """被覆率 profile が先頭から見て初めて 0.5 を超える位置（画素の中心のあいだを直線で補う）。"""
    i = int(np.argmax(profile >= 0.5))
    if i == 0:
        return 0.0
    a, b = profile[i - 1], profile[i]
    return i - 0.5 + (0.5 - a) / (b - a)


class Card:
    """1枚の札の寸法。位置は画像の px、字の矩形は札の外形の左上を原点にした px。"""

    def __init__(self, rgb: np.ndarray, box: tuple[int, int, int, int], text: str):
        luma = rgb @ LUMA
        left, top, right, bottom = box
        w, h = right - left, bottom - top
        ys = range(top + int(h * 0.3), top + int(h * 0.7))
        xs = range(left + int(w * 0.3), left + int(w * 0.7))
        # 縁の色は縁の中ほど、地の色は左の縁のすぐ内側（字の無いところ）で測る
        self.edge_color = np.median(rgb[ys.start:ys.stop, left + int(w * 0.03)], axis=0)
        self.face_color = np.median(rgb[ys.start:ys.stop, left + int(w * 0.12)], axis=0)
        edge_luma = float(self.edge_color @ LUMA)
        face_luma = float(self.face_color @ LUMA)

        def measure(lines: list[np.ndarray]) -> tuple[float, float]:
            """縁を外から内へ横切る線ごとに、外形と内側の境目の位置を求めて中央値を返す。"""
            outer, inner = [], []
            for line in lines:
                # 外側に地（白）があれば外形の境目の画素も数える。札だけを撮った画像は先頭が外形
                o = edge_position(line, 255.0, edge_luma) if line[0] > edge_luma + 20 else 0.0
                start = int(o) + 2
                outer.append(o)
                inner.append(start + edge_position(line[start:], edge_luma, face_luma))
            return float(np.median(outer)), float(np.median(inner))

        pad = 3
        span = int(w * 0.12)
        x_lo, x_hi = max(0, left - pad), min(rgb.shape[1], right + pad)
        y_lo, y_hi = max(0, top - pad), min(rgb.shape[0], bottom + pad)
        lo, li = measure([luma[y, x_lo:left + span] for y in ys])
        ro, ri = measure([luma[y, right - span:x_hi][::-1] for y in ys])
        to, ti = measure([luma[y_lo:top + span, x] for x in xs])
        bo, bi = measure([luma[bottom - span:y_hi, x][::-1] for x in xs])
        self.left, self.right = x_lo + lo, x_hi - ro
        self.top, self.bottom = y_lo + to, y_hi - bo
        self.width = self.right - self.left
        self.height = self.bottom - self.top
        self.edges = dict(zip(SIDES, (li - lo, ri - ro, ti - to, bi - bo)))
        self.glyphs = self._glyphs(luma, face_luma, text)

    def _glyphs(self, luma: np.ndarray, face_luma: float, text: str) -> list[tuple[str, float, float, float, float]]:
        """各字のインクの外接矩形 (字, 左, 上, 右, 下)。右の列から、列ごとに上から。"""
        margin = max(self.edges.values()) + 3
        x0, x1 = int(self.left + margin), int(self.right - margin)
        y0, y1 = int(self.top + margin), int(self.bottom - margin)
        ink = np.clip((face_luma - luma[y0:y1, x0:x1]) / face_luma, 0.0, 1.0)
        mask = ink >= 0.5
        per_column = (len(text) + 2) // 3
        columns = [text[i:i + per_column] for i in range(0, len(text), per_column)]
        col_runs = runs(np.where(mask.any(axis=0))[0], min_gap=int(self.width * 0.05))
        if len(col_runs) != len(columns):
            raise SystemExit(f"列の数が合わない: {len(col_runs)}（想定 {len(columns)}）")
        out = []
        for chars, (cx0, cx1) in zip(columns, reversed(col_runs)):
            rows = runs(np.where(mask[:, cx0:cx1 + 1].any(axis=1))[0], min_gap=1)
            # 字の中の隙間（「こ」など）は字と字の間より狭いので、狭い隙間からつなぐ
            while len(rows) > len(chars):
                gaps = [rows[i + 1][0] - rows[i][1] for i in range(len(rows) - 1)]
                i = int(np.argmin(gaps))
                rows[i:i + 2] = [(rows[i][0], rows[i + 1][1])]
            if len(rows) != len(chars):
                raise SystemExit(f"字の数が合わない: {len(rows)}（想定 {len(chars)}「{chars}」）")
            for char, (ry0, ry1) in zip(chars, rows):
                gx = np.where(mask[ry0:ry1 + 1, cx0:cx1 + 1].any(axis=0))[0]
                # 境目の画素から小数の位置を求めるため、まわりを2画素ずつ含める
                bx0, bx1 = cx0 + int(gx[0]) - 2, cx0 + int(gx[-1]) + 3
                by0, by1 = ry0 - 2, ry1 + 3
                sub = ink[by0:by1, bx0:bx1]
                px, py = sub.max(axis=0), sub.max(axis=1)
                gl, gr = bx0 + crossing(px), bx1 - crossing(px[::-1])
                gt, gb = by0 + crossing(py), by1 - crossing(py[::-1])
                out.append((
                    char,
                    x0 + gl - self.left,
                    y0 + gt - self.top,
                    x0 + gr - self.left,
                    y0 + gb - self.top,
                ))
        return out


def hex_color(rgb: np.ndarray) -> str:
    return "#" + "".join(f"{int(round(c)):02X}" for c in rgb)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--reference", type=pathlib.Path, default=REFERENCE)
    parser.add_argument("--shots", type=pathlib.Path, default=SHOTS, help="torifuda_reference_<番号>.png のあるところ")
    args = parser.parse_args()

    ref_rgb = load_rgb(args.reference)
    ref_boxes = find_cards(ref_rgb)
    if len(ref_boxes) != len(CARDS):
        raise SystemExit(f"参照画像の札の数が合わない: {len(ref_boxes)}")
    worst_edge = worst_pos = worst_size = worst_all = 0.0
    for (no, text), ref_box in zip(CARDS, ref_boxes):
        shot = args.shots / f"torifuda_reference_{no}.png"
        app_rgb = load_rgb(shot)
        app_boxes = find_cards(app_rgb)
        if len(app_boxes) != 1:
            raise SystemExit(f"{shot.name}: 札が1枚に見つからない（{len(app_boxes)}）")
        ref = Card(ref_rgb, ref_box, text)
        app = Card(app_rgb, app_boxes[0], text)
        # アプリの札を参照画像の大きさにそろえて比べる
        sx, sy = ref.width / app.width, ref.height / app.height
        print(f"■ {no}番（外形 参照 {ref.width:.1f}×{ref.height:.1f}px → アプリ {app.width:.1f}×{app.height:.1f}px）")
        for side in SIDES:
            s = sx if side in ("左", "右") else sy
            diff = app.edges[side] * s - ref.edges[side]
            worst_edge = max(worst_edge, abs(diff))
        print("  縁の太さ " + "  ".join(
            f"{side} {ref.edges[side]:.2f}→{app.edges[side]:.2f}" for side in SIDES))
        print(f"  縁の色 {hex_color(ref.edge_color)}→{hex_color(app.edge_color)}"
              f"  地の色 {hex_color(ref.face_color)}→{hex_color(app.face_color)}")
        print("  字  参照（左, 上, 幅, 高さ）         アプリ                           ずれ")
        for (char, rl, rt, rr, rb), (_, al, at, ar, ab) in zip(ref.glyphs, app.glyphs):
            al, ar, at, ab = al * sx, ar * sx, at * sy, ab * sy
            d = (al - rl, at - rt, (ar - al) - (rr - rl), (ab - at) - (rb - rt))
            note = "  ← 位置は目視の調整（位置の判定から除外）" if char in HAND_ADJUSTED else ""
            print(f"  {char}  ({rl:6.1f},{rt:6.1f},{rr - rl:5.1f},{rb - rt:5.1f})"
                  f"  ({al:6.1f},{at:6.1f},{ar - al:5.1f},{ab - at:5.1f})"
                  f"  ({d[0]:+5.1f},{d[1]:+5.1f},{d[2]:+5.1f},{d[3]:+5.1f}){note}")
            worst_all = max(worst_all, abs(d[0]), abs(d[1]))
            # 目で見て直したのは位置だけなので、大きさはどの字も比べる
            worst_size = max(worst_size, abs(d[2]), abs(d[3]))
            if char not in HAND_ADJUSTED:
                worst_pos = max(worst_pos, abs(d[0]), abs(d[1]))
    ok = worst_edge <= EDGE_TOLERANCE and worst_pos <= GLYPH_TOLERANCE and worst_size <= GLYPH_TOLERANCE
    print()
    print(f"縁の太さのずれ: 最大 {worst_edge:.2f}px（目標 ±{EDGE_TOLERANCE:g}px）")
    print(f"字の位置（インクの左上）のずれ: 最大 {worst_pos:.2f}px"
          f"（「の」「で」を除く。含めると {worst_all:.2f}px。目標 ±{GLYPH_TOLERANCE:g}px）")
    print(f"字の大きさ（インクの幅・高さ）のずれ: 最大 {worst_size:.2f}px（すべての字。目標 ±{GLYPH_TOLERANCE:g}px）")
    print("目標の範囲内" if ok else "目標を外れている")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
