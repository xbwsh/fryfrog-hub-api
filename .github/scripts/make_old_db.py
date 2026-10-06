"""冒烟测试用：造一个「旧 schema」的库，模拟 NAS 上升级前的 db。

只保留旧版本确实存在的列，这样容器启动时 create_all 不会重建这些表，
_ensure_columns 才会真正走补列分支。
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

PASSWORD = "smoke-pw"


def _password_hash(password: str) -> str:
    """按当前项目的口令哈希格式生成（bcrypt，见 core/security.hash_password）。"""
    import bcrypt

    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def create(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pw_hash = _password_hash(PASSWORD)

    con = sqlite3.connect(path)
    con.executescript(
        """
        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username VARCHAR NOT NULL UNIQUE,
            password_hash VARCHAR NOT NULL,
            role VARCHAR NOT NULL,
            enabled BOOLEAN NOT NULL DEFAULT 1,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
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
            enabled BOOLEAN DEFAULT 1,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        """
    )
    con.execute(
        "INSERT INTO users (username, password_hash, role) VALUES (?,?,?)",
        ("admin", pw_hash, "ADMIN"),
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
    print(f"old-schema db ready: {path}")


if __name__ == "__main__":
    create(Path(sys.argv[1] if len(sys.argv) > 1 else "smoke/db/fryfrog.db"))
