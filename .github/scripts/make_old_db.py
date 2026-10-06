"""冒烟测试用：造一个「旧 schema」的库，模拟 NAS 上升级前的 db。

只保留旧版本确实存在的列，这样容器启动时 create_all 不会重建这些表，
_ensure_columns 才会真正走补列分支。

注意：这里不建 users 表、不生成口令哈希——冒烟测试用 AUTH_ENABLED=false 跑，
无需登录（少一个会把整条流程带崩的失败点）。
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


def create(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path)
    con.executescript(
        """
        -- 旧版 videos 表：只有当时存在的列，缺新列。
        -- 这样 create_all 不会重建它，_ensure_columns 才会真正走补列分支。
        CREATE TABLE videos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title VARCHAR NOT NULL,
            file_path VARCHAR NOT NULL,
            file_name VARCHAR NOT NULL,
            duration_seconds FLOAT,
            resolution VARCHAR,
            library_id INTEGER,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE video_series (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title VARCHAR NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE media_libraries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name VARCHAR NOT NULL,
            path VARCHAR NOT NULL,
            type VARCHAR NOT NULL,
            enabled BOOLEAN NOT NULL DEFAULT 1,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        """
    )
    con.execute(
        "INSERT INTO media_libraries (name, path, type) VALUES (?,?,?)",
        ("旧库", "/data/media/video", "VIDEO"),
    )
    con.execute(
        "INSERT INTO videos (title, file_path, file_name, library_id) VALUES (?,?,?,?)",
        ("旧片子", "/data/media/video/old.mp4", "old.mp4", 1),
    )
    con.commit()
    con.close()
    print("old-schema db ready:", path)
    print("  media_libraries 列:", sorted(r[1] for r in sqlite3.connect(path).execute("pragma table_info(media_libraries)")))
    print("  videos 列:", sorted(r[1] for r in sqlite3.connect(path).execute("pragma table_info(videos)")))


if __name__ == "__main__":
    create(Path(sys.argv[1] if len(sys.argv) > 1 else "smoke/db/fryfrog.db"))
