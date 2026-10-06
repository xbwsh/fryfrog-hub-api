"""冒烟测试用：校验容器启动后，老库已被就地升级且旧数据完好。"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

REQUIRED_TABLES = ("videos", "video_series", "media_libraries", "users", "auth_tokens")
REQUIRED_VIDEO_COLUMNS = {"last_seen_at", "missing_since", "media_probed_mtime"}


def verify(path: Path) -> None:
    con = sqlite3.connect(path)
    tables = {row[0] for row in con.execute("select name from sqlite_master where type='table'")}
    video_cols = {row[1] for row in con.execute("pragma table_info(videos)")}
    lib_cols = {row[1] for row in con.execute("pragma table_info(media_libraries)")}
    lib_rows = con.execute("select count(*) from media_libraries").fetchone()[0]
    video_rows = con.execute("select count(*) from videos").fetchone()[0]
    old_title = con.execute("select title from videos where file_path like '%old.mp4'").fetchone()
    con.close()

    problems: list[str] = []
    if missing := REQUIRED_VIDEO_COLUMNS - video_cols:
        problems.append(f"videos 未补上新列: {sorted(missing)}")
    if "enable_scraping" not in lib_cols:
        problems.append("media_libraries 未补上 enable_scraping")
    if lib_rows != 1:
        problems.append(f"media_libraries 旧数据行数被改动: {lib_rows} != 1")
    if video_rows != 1:
        problems.append(f"videos 旧数据行数被改动: {video_rows} != 1")
    if not old_title:
        problems.append("videos 里那条旧记录丢了")
    if missing_tables := [t for t in REQUIRED_TABLES if t not in tables]:
        problems.append(f"缺表: {missing_tables}")

    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print("schema OK：新列已补、旧数据完好、表齐全")


if __name__ == "__main__":
    verify(Path(sys.argv[1] if len(sys.argv) > 1 else "smoke/db/fryfrog.db"))
