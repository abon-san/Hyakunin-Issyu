#!/usr/bin/env python3
"""生成した読み上げ音声（generate_all.py の出力）をアプリのアセットに入れ、中身を確かめる。

    python3 tools/voice/install_voice_assets.py SRC_DIR [--dest DIR] [--dry-run]
    python3 tools/voice/install_voice_assets.py --check [--dest DIR]

SRC_DIR は generate_all.py の OUT_DIR（timings.json と ogg/ がある）。DIR の既定は
app/src/main/assets/voice/himari。--dry-run は SRC_DIR を確かめるだけで書かない。
--check は入れてあるもの（DIR）だけを確かめる。

音声を作り直したら、同じように実行すれば入れ替わる。DIR はまるごと新しい版に置き換え（前の版のファイルは
残さない）、何が変わったか（変更・追加・削除）を出す。新しい版は隣の隠しフォルダに書いて確かめてから
入れ替えるので、途中で失敗しても入れてあるものは壊れない。

確かめること（1 つでも外れたら、アセットには何も書かずに終わる）:
  * 100 首と序歌の上の句・下の句が 1 つずつそろっている（別読みの _alt は入れない）
  * 時刻表（kanaStartMs）の数が、アプリの読み（data/PoemsPart1.kt・PoemsPart2.kt・Joka.kt）の
    仮名の数（空白を除く）と同じ
  * 時刻表の読み（reading）が、アプリの読みと同じ
  * durationMs が正で、kanaStartMs が 0 以上・小さい順・durationMs 以下
  * OGG ファイルがあり、Ogg Vorbis か Ogg Opus である
アプリ用の timings.json には、アプリが使う項目（file・poem・part・reading・durationMs・kanaStartMs）だけを書く。
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_DATA = ROOT / "app" / "src" / "main" / "java" / "io" / "github" / "abonsan" / "hyakunin" / "data"
POEM_FILES = (APP_DATA / "PoemsPart1.kt", APP_DATA / "PoemsPart2.kt")
JOKA_FILE = APP_DATA / "Joka.kt"
DEFAULT_DEST = ROOT / "app" / "src" / "main" / "assets" / "voice" / "himari"
TIMINGS = "timings.json"
PARTS = ("kami", "shimo")


class CheckError(Exception):
    pass


# ---------------------------------------------------------------- アプリの読み


def app_readings() -> dict[tuple[object, str], str]:
    """(歌番号 1..100 または "joka", "kami"|"shimo") → 読み。アプリのソースから取り出す。"""
    readings: dict[tuple[object, str], str] = {}
    pat = re.compile(r'no = (\d+),.*?kamiYomi = "([^"]*)",\s*shimoYomi = "([^"]*)"', re.S)
    for path in POEM_FILES:
        for m in pat.finditer(path.read_text("utf-8")):
            no = int(m.group(1))
            readings[(no, "kami")] = m.group(2)
            readings[(no, "shimo")] = m.group(3)
    joka = JOKA_FILE.read_text("utf-8")
    for part in PARTS:
        m = re.search(rf'val {part}Yomi: String = "([^"]*)"', joka)
        if not m:
            raise CheckError(f"{JOKA_FILE.name} に {part}Yomi が見つからない")
        readings[("joka", part)] = m.group(1)
    nos = sorted(k[0] for k in readings if k[0] != "joka" and k[1] == "kami")
    if nos != list(range(1, 101)):
        raise CheckError(f"アプリの歌が 100 首そろっていない（{len(nos)} 首）")
    return readings


def strip_spaces(text: str) -> str:
    """空白（全角スペースを含む）を除く。アプリの kanaCount と同じ数え方。"""
    return "".join(c for c in text if not c.isspace())


# ---------------------------------------------------------------- 時刻表


def clip_key(entry: dict) -> tuple[object, str]:
    poem, part = entry.get("poem"), entry.get("part")
    if part not in PARTS:
        raise CheckError(f"{entry.get('file')}: part が {part!r}")
    if poem == "joka":
        return "joka", part
    if isinstance(poem, int) and not isinstance(poem, bool) and 1 <= poem <= 100:
        return poem, part
    raise CheckError(f"{entry.get('file')}: poem が {poem!r}")


def expected_file(key: tuple[object, str]) -> str:
    poem, part = key
    return f"joka_{part}.ogg" if poem == "joka" else f"{poem:03d}_{part}.ogg"


def ogg_codec(path: Path) -> str:
    head = path.read_bytes()[:256]
    if not head.startswith(b"OggS"):
        raise CheckError(f"{path.name}: Ogg ではない")
    if b"\x01vorbis" in head:
        return "vorbis"
    if b"OpusHead" in head:
        return "opus"
    raise CheckError(f"{path.name}: Vorbis でも Opus でもない")


def check_clips(clips: list[dict], ogg_dir: Path, readings: dict) -> tuple[list[dict], dict[str, int]]:
    """別読みを除いた時刻表を確かめ、アプリ用の項目だけにして返す。2 つめは codec ごとの数。"""
    out: dict[tuple[object, str], dict] = {}
    codecs: dict[str, int] = {}
    for entry in clips:
        if entry.get("alt"):
            continue
        key = clip_key(entry)
        name = entry.get("file")
        if name != expected_file(key):
            raise CheckError(f"{key}: ファイル名が {name!r}（{expected_file(key)} のはず）")
        if key in out:
            raise CheckError(f"{name}: 2 つある")
        reading = readings[key]
        if strip_spaces(entry.get("reading", "")) != strip_spaces(reading):
            raise CheckError(f"{name}: 読みが違う（時刻表 {entry.get('reading')!r}、アプリ {reading!r}）")
        duration = entry.get("durationMs")
        if not isinstance(duration, int) or duration <= 0:
            raise CheckError(f"{name}: durationMs が {duration!r}")
        starts = entry.get("kanaStartMs")
        if not isinstance(starts, list) or not all(isinstance(t, int) for t in starts):
            raise CheckError(f"{name}: kanaStartMs が整数の並びではない")
        kana = len(strip_spaces(reading))
        if len(starts) != kana:
            raise CheckError(f"{name}: kanaStartMs が {len(starts)} 個（読みの仮名は {kana}）")
        if starts and (starts[0] < 0 or starts[-1] > duration):
            raise CheckError(f"{name}: kanaStartMs が 0〜{duration} の外（{starts[0]}〜{starts[-1]}）")
        if any(b < a for a, b in zip(starts, starts[1:])):
            raise CheckError(f"{name}: kanaStartMs が小さい順ではない")
        path = ogg_dir / name
        if not path.is_file() or path.stat().st_size == 0:
            raise CheckError(f"{name}: OGG ファイルがない")
        codec = ogg_codec(path)
        codecs[codec] = codecs.get(codec, 0) + 1
        out[key] = {"file": name, "poem": key[0], "part": key[1], "reading": entry["reading"],
                    "durationMs": duration, "kanaStartMs": starts}
    missing = [expected_file(k) for k in readings if k not in out]
    if missing:
        raise CheckError(f"足りないクリップが {len(missing)} 個: {', '.join(missing[:8])}")
    ordered = [out[("joka", p)] for p in PARTS] + [out[(n, p)] for n in range(1, 101) for p in PARTS]
    return ordered, codecs


def load_timings(path: Path) -> dict:
    if not path.is_file():
        raise CheckError(f"{path} がない")
    data = json.loads(path.read_text("utf-8"))
    if not isinstance(data.get("clips"), list):
        raise CheckError(f"{path}: clips がない")
    return data


# ---------------------------------------------------------------- メイン


def install(src: Path, dest: Path, readings: dict, dry_run: bool = False) -> None:
    data = load_timings(src / TIMINGS)
    ogg_dir = src / "ogg" if (src / "ogg").is_dir() else src
    clips, codecs = check_clips(data["clips"], ogg_dir, readings)
    app_json = {key: data[key] for key in ("version", "voice", "codec") if key in data}
    app_json["clips"] = clips
    if dry_run:
        print(f"{src}: 入れられる（--dry-run なので書かない）")
        report(ogg_dir, clips, codecs, json_bytes=len(app_json_text(app_json).encode()))
        return
    # 隣の隠しフォルダ（AAPT は「.」で始まるものをアセットに入れない）に書いて確かめてから、まるごと入れ替える。
    # 途中で失敗しても、入れてあるものはそのまま残り、前の版のファイルも混ざらない
    stage = dest.with_name(f".{dest.name}.new")
    backup = dest.with_name(f".{dest.name}.old")
    try:
        for d in (stage, backup):
            if d.exists():
                shutil.rmtree(d)
        stage.mkdir(parents=True)
        for clip in clips:
            shutil.copyfile(ogg_dir / clip["file"], stage / clip["file"])
        (stage / TIMINGS).write_text(app_json_text(app_json), "utf-8")
        check_installed(stage, readings, quiet=True)
        changes = compare(dest, stage)
        if dest.exists():
            dest.rename(backup)
        try:
            stage.rename(dest)
        except OSError:
            # 入れ替えられなければ前の版に戻す
            if backup.exists():
                backup.rename(dest)
            raise
    finally:
        if stage.exists():
            shutil.rmtree(stage)
        if backup.exists() and dest.exists():
            shutil.rmtree(backup)
    print(changes)
    report(dest, clips, codecs)


def app_json_text(app_json: dict) -> str:
    return json.dumps(app_json, ensure_ascii=False, separators=(",", ":")) + "\n"


def compare(old: Path, new: Path) -> str:
    """入れてあるもの（[old]）から何が変わるか。"""
    if not old.is_dir():
        return f"新しく入れる: {sum(1 for p in new.iterdir() if p.is_file())} ファイル"
    before = {p.name: p.read_bytes() for p in old.iterdir() if p.is_file()}
    after = {p.name: p.read_bytes() for p in new.iterdir() if p.is_file()}
    added = sorted(after.keys() - before.keys())
    removed = sorted(before.keys() - after.keys())
    changed = sorted(n for n in after.keys() & before.keys() if after[n] != before[n])
    same = len(after) - len(added) - len(changed)
    head = lambda names: ", ".join(names[:6]) + (" ほか" if len(names) > 6 else "")
    lines = [f"入れ替える: 変更 {len(changed)}、追加 {len(added)}、削除 {len(removed)}、同じ {same}"]
    for label, names in (("変更", changed), ("追加", added), ("削除", removed)):
        if names:
            lines.append(f"  {label}: {head(names)}")
    return "\n".join(lines)


def check_installed(dest: Path, readings: dict, quiet: bool = False) -> None:
    data = load_timings(dest / TIMINGS)
    clips, codecs = check_clips(data["clips"], dest, readings)
    listed = {c["file"] for c in clips} | {TIMINGS}
    extra = sorted(p.name for p in dest.iterdir() if p.name not in listed)
    if extra:
        raise CheckError(f"時刻表にないファイルがある: {', '.join(extra)}")
    if not quiet:
        report(dest, clips, codecs)


def report(dest: Path, clips: list[dict], codecs: dict[str, int], json_bytes: int | None = None) -> None:
    ogg_bytes = sum((dest / c["file"]).stat().st_size for c in clips)
    if json_bytes is None:
        json_bytes = (dest / TIMINGS).stat().st_size
    rel = dest.relative_to(ROOT) if dest.is_relative_to(ROOT) else dest
    print(f"{rel}: {len(clips)} clips ({len(clips) // 2 - 1} poems + joka), "
          f"codec {', '.join(f'{k} x{v}' for k, v in sorted(codecs.items()))}, "
          f"ogg {ogg_bytes / 1e6:.2f} MB, {TIMINGS} {json_bytes / 1e3:.1f} kB")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", type=Path, nargs="?", help="generate_all.py の OUT_DIR")
    ap.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    ap.add_argument("--check", action="store_true", help="入れてあるアセットだけを確かめる")
    ap.add_argument("--dry-run", action="store_true", help="SRC_DIR を確かめるだけで書かない")
    args = ap.parse_args()
    if not args.check and args.src is None:
        ap.error("SRC_DIR か --check を指定する")
    try:
        readings = app_readings()
        if args.check:
            check_installed(args.dest.resolve(), readings)
        else:
            install(args.src.resolve(), args.dest.resolve(), readings, dry_run=args.dry_run)
    except CheckError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
