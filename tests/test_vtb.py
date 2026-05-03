"""Юнит-тесты scrape_vtb_coins (без сети)."""
from __future__ import annotations

import json
from pathlib import Path

from scrape_vtb_coins import (
    BASE_SITE,
    build_coin_url,
    build_list_url,
    build_payload,
    coin_to_output,
    dedupe_key,
    parse_list_response,
)

FIXTURES = Path(__file__).parent / "fixtures"


def test_build_list_url():
    assert build_list_url(1) == f"{BASE_SITE}/api/bff/api/v1/coin/list?page=1"
    assert build_list_url(33) == f"{BASE_SITE}/api/bff/api/v1/coin/list?page=33"


def test_build_payload():
    assert build_payload() == {"filters": []}
    assert build_payload(["x"]) == {"filters": ["x"]}


def test_parse_list_response_from_fixture():
    data = json.loads((FIXTURES / "vtb_list_page1.json").read_text(encoding="utf-8"))
    rows, max_page = parse_list_response(data)
    assert max_page == 3
    assert len(rows) == 2


def test_coin_to_output_fixture_rows():
    data = json.loads((FIXTURES / "vtb_list_page1.json").read_text(encoding="utf-8"))
    gold = coin_to_output(data["coins"][0])
    assert gold is not None
    assert gold["name"] == "Золотой червонец"
    assert gold["article"] == "5214-0009"
    assert "sku" not in gold
    assert gold["metal"] == "Золото"
    assert gold["weight_g"] == 7.78
    assert gold["price1"] == 132900.0
    assert "price2" not in gold and "price3" not in gold
    assert gold["nominal"] == "10 рублей РФ"
    assert "article=" in gold["url"] and "id=" in gold["url"]

    silver = coin_to_output(data["coins"][1])
    assert silver is not None
    assert silver["article"] == "30059"
    assert silver["metal"] == "Серебро"
    assert silver["price1"] == 11800.0


def test_coin_to_output_empty_returns_none():
    assert coin_to_output({}) is None
    assert coin_to_output({"name": "", "article": ""}) is None


def test_build_coin_url_only_article():
    u = build_coin_url(article="5214-0009", coin_id=None)
    assert "article=5214-0009" in u
    assert "id=" not in u


def test_dedupe_key_prefers_uuid():
    row = {"id": "uuid-1", "article": "A"}
    flat = {"url": "http://x", "article": "A"}
    assert dedupe_key(row, flat) == "uuid-1"
