"""Filter the HTTP access records emitted by Uvicorn."""

from logging import LogRecord


def filter_access_log(record: LogRecord) -> bool:
    # h11 和 httptools 均传入 client, method, target, version, status。
    args = record.args
    if not isinstance(args, tuple) or len(args) != 5:
        return False
    client, method, target, version, status = args
    if not isinstance(status, int) or status < 400 or not isinstance(target, str):
        return False

    # 查询字符串可能含媒体签名或 Subsonic 认证参数。
    record.args = (client, method, target.partition("?")[0], version, status)
    return True
