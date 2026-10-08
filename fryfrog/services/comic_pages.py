"""漫画页图：目录列文件 / zip·cbz·rar·cbr·7z 读条目，自然序页序；PDF 按页渲染。"""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.core.natural_order import natural_key
from fryfrog.models.comic import ComicChapter

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif"}
ZIP_EXTS = {".zip", ".cbz"}
RAR_EXTS = {".rar", ".cbr"}
SEVEN_EXTS = {".7z"}
PDF_EXTS = {".pdf"}
ARCHIVE_EXTS = ZIP_EXTS | RAR_EXTS | SEVEN_EXTS | PDF_EXTS

# 渲染目标宽度（px）：缩放按 PDF 页宽折算，限制在 1x~4x
PDF_TARGET_WIDTH = 1600.0
PDF_JPEG_QUALITY = 85
# 压缩包单条目读取上限：页面图片不可能这么大；构造的 cbz（zip bomb）
# 一个条目解出数 GB，zf.read 直接整块进内存会打爆进程
MAX_ENTRY_BYTES = 256 * 1024 * 1024
# PDF 渲染像素上限（宽×高）：异常尺寸的 PDF 页按原生尺寸渲染会产生
# 10^10 像素级分配。超过上限按比例缩小到边界内。
MAX_RENDER_PIXELS = 40_000_000


def is_image_name(name: str) -> bool:
    if not name:
        return False
    lower = name.lower()
    base = Path(lower).name
    if base == "cover.jpg" or base.endswith(".cover.jpg"):
        return False
    return Path(lower).suffix in IMAGE_SUFFIXES


def _open_archive(path: Path):
    suffix = path.suffix.lower()
    if suffix in ZIP_EXTS or _looks_like_zip(path):
        return zipfile.ZipFile(path)
    if suffix in RAR_EXTS:
        import rarfile

        rf = rarfile.RarFile(path)
        return rf
    if suffix in SEVEN_EXTS:
        import py7zr

        return py7zr.SevenZipFile(path, mode="r")
    # 兜底：按 zip 试开
    return zipfile.ZipFile(path)


def _looks_like_zip(path: Path) -> bool:
    try:
        return path.read_bytes()[:4] == b"PK\x03\x04"
    except OSError:
        return False


def _open_pdf(path: Path):
    try:
        import pypdfium2 as pdfium
    except ImportError:
        raise BadRequestException("未安装 pypdfium2：pip install pypdfium2")
    return pdfium.PdfDocument(str(path))


def _pdf_page_names(path: Path) -> list[str]:
    with _open_pdf(path) as doc:
        return [f"page_{i:05d}.jpg" for i in range(len(doc))]


def _render_pdf_page(path: Path, index: int) -> bytes:
    with _open_pdf(path) as doc:
        if index < 0 or index >= len(doc):
            raise ResourceNotFoundException("ComicPage", "index", index)
        page = doc[index]
        width = page.get_width() or 595.0
        height = page.get_height() or 842.0
        scale = min(4.0, max(1.0, PDF_TARGET_WIDTH / width))
        # 异常大页（恶意/损坏 PDF）：scale 钳到 1.0 后仍按原生尺寸渲染会产生
        # 海量像素分配，必须按像素预算二次收缩
        if width * height * scale * scale > MAX_RENDER_PIXELS:
            scale = (MAX_RENDER_PIXELS / (width * height)) ** 0.5
        image = page.render(scale=scale).to_pil()
        if image.mode != "RGB":
            image = image.convert("RGB")
        buf = io.BytesIO()
        image.save(buf, format="JPEG", quality=PDF_JPEG_QUALITY)
        return buf.getvalue()


def _list_archive_names(path: Path) -> list[str]:
    if path.suffix.lower() in SEVEN_EXTS:
        import py7zr

        with py7zr.SevenZipFile(path, mode="r") as zf:
            names = [n for n in zf.getnames() if not n.endswith("/")]
        return [n for n in names if is_image_name(n)]
    with _open_archive(path) as zf:
        if hasattr(zf, "infolist"):
            names = []
            for info in zf.infolist():
                is_dir = info.is_dir() if callable(getattr(info, "is_dir", None)) else bool(getattr(info, "is_dir", False))
                if not is_dir:
                    names.append(info.filename)
        else:
            names = [n for n in zf.getnames() if not n.endswith("/")]
    return [n for n in names if is_image_name(n)]


def list_page_names(chapter: ComicChapter) -> list[str]:
    path = Path(chapter.file_path)
    if path.suffix.lower() in PDF_EXTS:
        return _pdf_page_names(path)
    if (chapter.type or "").upper() == "ARCHIVE":
        names = _list_archive_names(path)
        names.sort(key=natural_key)
        return names
    if not path.is_dir():
        raise ResourceNotFoundException("ComicChapter", "id", chapter.id)
    names = [p.name for p in path.iterdir() if p.is_file() and is_image_name(p.name)]
    names.sort(key=natural_key)
    return names


def page_count(chapter: ComicChapter) -> int | None:
    try:
        return len(list_page_names(chapter))
    except Exception:
        return None


def read_page(chapter: ComicChapter, index: int) -> tuple[bytes, str]:
    path = Path(chapter.file_path)
    if path.suffix.lower() in PDF_EXTS:
        return _render_pdf_page(path, index), "image/jpeg"
    names = list_page_names(chapter)
    if index < 0 or index >= len(names):
        raise ResourceNotFoundException("ComicPage", "index", index)
    name = names[index]
    if (chapter.type or "").upper() == "ARCHIVE":
        data = _read_archive_entry(Path(chapter.file_path), name)
    else:
        data = (Path(chapter.file_path) / name).read_bytes()
    return data, media_type_of(name)


def _read_archive_entry(path: Path, name: str) -> bytes:
    suffix = path.suffix.lower()
    # zip bomb 防护：读前先看声明的解压尺寸，超限拒绝。漫画页图片不可能
    # 达到 256MB；不设上限的话一个几 MB 的 cbz 就能让翻页请求 OOM。
    if suffix not in SEVEN_EXTS:
        with _open_archive(path) as zf:
            try:
                info = zf.getinfo(name)
            except Exception:
                info = None
            if info is not None and getattr(info, "file_size", 0) > MAX_ENTRY_BYTES:
                raise BadRequestException("压缩包内单文件超出大小限制")
            return zf.read(name)
    import py7zr

    with py7zr.SevenZipFile(path, mode="r") as zf:
        extracted = zf.read([name])
        target = extracted.get(name)
        if target is None:
            # 兼容路径键差异
            for key, bio in extracted.items():
                if Path(key).name == Path(name).name:
                    target = bio
                    break
        if target is None:
            raise ResourceNotFoundException("ComicPage", "name", name)
        if hasattr(target, "getvalue"):
            data = target.getvalue()
        elif isinstance(target, (bytes, bytearray)):
            data = bytes(target)
        else:
            data = Path(target).read_bytes()
        if len(data) > MAX_ENTRY_BYTES:
            raise BadRequestException("压缩包内单文件超出大小限制")
        return data


def media_type_of(filename: str) -> str:
    lower = (filename or "").lower()
    if lower.endswith(".png"):
        return "image/png"
    if lower.endswith(".webp"):
        return "image/webp"
    if lower.endswith(".gif"):
        return "image/gif"
    if lower.endswith(".bmp"):
        return "image/bmp"
    if lower.endswith(".avif"):
        return "image/avif"
    return "image/jpeg"
