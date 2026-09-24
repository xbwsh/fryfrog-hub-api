from __future__ import annotations

import logging
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.models.auth import AuthToken
from fryfrog.models.user import User
from fryfrog.models.video import WatchProgress

logger = logging.getLogger(__name__)


def migrate_legacy_data(db: Session) -> None:
    """把 user_id 为空的观看进度迁给第一个 ADMIN。"""
    admin = db.scalar(select(User).where(User.role == "ADMIN").order_by(User.id.asc()))
    if admin is None:
        return
    rows = db.scalars(select(WatchProgress).where(WatchProgress.user_id.is_(None))).all()
    for row in rows:
        exists = db.scalar(
            select(WatchProgress).where(
                WatchProgress.user_id == admin.id,
                WatchProgress.video_id == row.video_id,
            )
        )
        if exists:
            db.delete(row)
        else:
            row.user_id = admin.id
    if rows:
        db.flush()
        logger.info("Migrated legacy watch progress rows to admin id=%s", admin.id)


def cleanup_expired_tokens(db: Session) -> int:
    now = datetime.now()
    rows = db.scalars(select(AuthToken).where(AuthToken.expires_at < now)).all()
    for row in rows:
        db.delete(row)
    if rows:
        db.flush()
        logger.info("Cleaned %d expired auth tokens", len(rows))
    return len(rows)
