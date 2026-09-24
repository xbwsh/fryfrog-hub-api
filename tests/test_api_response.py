from fryfrog.core.api_response import ApiResponse, PageResponse


def test_api_response():
    r = ApiResponse.ok({"a": 1})
    assert r.success and r.data == {"a": 1}
    e = ApiResponse.error("boom")
    assert not e.success and e.message == "boom"


def test_page_response():
    p = PageResponse.of([1, 2], page=0, size=2, total_elements=5)
    assert p.totalPages == 3
    assert p.content == [1, 2]
