"""Filter incoming and outgoing HTTP access records."""

from logging import LogRecord

from httpx import URL


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


def filter_httpx_log(record: LogRecord) -> bool:
    if not isinstance(record.msg, str) or not record.msg.startswith("HTTP Request: "):
        return True
    args = record.args
    if not isinstance(args, tuple) or len(args) != 5:
        return False
    method, url, version, status, reason = args
    if not isinstance(status, int) or status < 400 or not isinstance(url, URL):
        return False
    clean_url = url.copy_with(query=None, fragment=None, username=None, password=None)
    record.args = (method, clean_url, version, status, reason)
    return True
