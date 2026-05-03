"""Юнит-тесты чистых функций scrape_sberbank_coins (без Playwright)."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from scrape_rshb_coins import Coin
from scrape_sberbank_coins import (
    DEFAULT_CITY,
    DEFAULT_CONDITION,
    DEFAULT_METAL_FILTERS,
    DEFAULT_PAGE_SIZE,
    API_BUYOUT_PATH,
    build_buyout_fetch_path,
    build_coin_url,
    build_payload,
    buyout_price_from_buyout_entity,
    entities_from_buyout_response,
    merge_buyout_into_catalog,
    merge_entities_with_metal_filters,
    parse_entity,
)

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# build_payload
# ---------------------------------------------------------------------------

def test_build_payload_defaults():
    body = build_payload()
    assert body["query"] == ""
    assert body["page"] == 0
    assert body["pageSize"] == DEFAULT_PAGE_SIZE
    assert body["city"] == DEFAULT_CITY
    assert body["condition"] == DEFAULT_CONDITION
    assert body["inDiscount"] is False
    assert body["vspCode"] is None
    assert body["metals"] == []
    assert body["sections"] == []
    assert body["categories"] == []


def test_build_payload_overrides():
    body = build_payload(
        page=2, page_size=99, city="Санкт-Петербург", condition=2, query="Сочи",
    )
    assert body["page"] == 2
    assert body["pageSize"] == 99
    assert body["city"] == "Санкт-Петербург"
    assert body["condition"] == 2
    assert body["query"] == "Сочи"


def test_build_payload_metals_single():
    body = build_payload(metals=["Золото"])
    assert body["metals"] == ["Золото"]


def test_build_payload_metals_empty_sequence_means_no_filter():
    body = build_payload(metals=[])
    assert body["metals"] == []


def test_default_metal_filters_tuple():
    assert DEFAULT_METAL_FILTERS == ("Золото", "Серебро", "Платина", "Палладий")


def test_merge_entities_with_metal_filters_distinct_ids():
    merged, raw = merge_entities_with_metal_filters(
        [
            ("Золото", [{"id": "a", "name": "A"}]),
            ("Серебро", [{"id": "b", "name": "B"}]),
        ]
    )
    assert raw == 2
    assert len(merged) == 2
    assert merged[0]["metal"] == "Золото" and merged[0]["id"] == "a"
    assert merged[1]["metal"] == "Серебро" and merged[1]["id"] == "b"


def test_merge_entities_with_metal_filters_duplicate_id_keeps_first_metal():
    merged, raw = merge_entities_with_metal_filters(
        [
            ("Золото", [{"id": "x", "price": 1}]),
            ("Серебро", [{"id": "x", "price": 2}]),
        ]
    )
    assert raw == 2
    assert len(merged) == 1
    assert merged[0]["metal"] == "Золото"
    assert merged[0]["price"] == 1


def test_merge_entities_with_metal_filters_duplicate_warning(caplog):
    import logging

    caplog.set_level(logging.WARNING)
    merge_entities_with_metal_filters(
        [
            ("Золото", [{"id": "dup"}]),
            ("Палладий", [{"id": "dup"}]),
        ]
    )
    assert any("dup" in r.message and "Палладий" in r.message for r in caplog.records)


def test_merge_entities_skips_row_without_id():
    merged, raw = merge_entities_with_metal_filters(
        [("Золото", [{"name": "no id"}, {"id": "1"}])]
    )
    assert raw == 2
    assert len(merged) == 1


def test_build_payload_required_keys_present():
    body = build_payload()
    expected = {
        "query", "priceSellMin", "priceSellMax", "parMin", "parMax",
        "massMin", "massMax", "metals", "sections", "categories",
        "condition", "vspCode", "inDiscount", "page", "pageSize", "city",
    }
    assert set(body.keys()) == expected


# ---------------------------------------------------------------------------
# build_coin_url
# ---------------------------------------------------------------------------

def test_build_coin_url():
    assert build_coin_url("5216-0060") == (
        "https://www.sberbank.ru/ru/person/mon#/coin/5216-0060"
    )


def test_build_buyout_fetch_path_defaults():
    path = build_buyout_fetch_path()
    assert path.startswith(API_BUYOUT_PATH + "?")
    assert "page=0" in path
    assert f"pageSize={DEFAULT_PAGE_SIZE}" in path
    assert "query=" in path


def test_build_buyout_fetch_path_query_and_page_size():
    path = build_buyout_fetch_path(page=1, page_size=100, query="золото")
    assert "page=1" in path
    assert "pageSize=100" in path
    assert "query=" in path


def test_entities_from_buyout_response_variants():
    assert entities_from_buyout_response([]) == []
    assert entities_from_buyout_response([{"id": "1"}]) == [{"id": "1"}]
    assert entities_from_buyout_response({"entities": [{"id": "a"}]}) == [{"id": "a"}]
    assert entities_from_buyout_response({"items": [{"id": "b"}]}) == [{"id": "b"}]


def test_buyout_price_from_buyout_entity_prefers_price_buy():
    assert buyout_price_from_buyout_entity({"priceBuy": 100.0, "price": 999.0}) == 100.0
    assert buyout_price_from_buyout_entity({"price": 50.0}) == 50.0
    assert buyout_price_from_buyout_entity({"id": "x"}) is None


def test_merge_buyout_into_catalog():
    catalog = [
        {"id": "5216-0060", "name": "A", "price": 1000.0, "priceBuy": None},
        {"id": "5217-0048", "name": "B", "price": 2000.0},
    ]
    buyout = [
        {"id": "5216-0060", "priceBuy": 800.0},
        {"id": "5217-0048", "price": 1500.0},
    ]
    merge_buyout_into_catalog(catalog, buyout)
    assert catalog[0]["priceBuy"] == 800.0
    assert catalog[1]["priceBuy"] == 1500.0


# ---------------------------------------------------------------------------
# parse_entity
# ---------------------------------------------------------------------------

@pytest.fixture
def sber_entities():
    return json.loads((FIXTURES / "sber_response.json").read_text(encoding="utf-8"))[
        "entities"
    ]


def test_parse_entity_full(sber_entities):
    coin = parse_entity(sber_entities[0])
    assert isinstance(coin, Coin)
    assert coin.url == "https://www.sberbank.ru/ru/person/mon#/coin/5216-0060"
    assert coin.catalog_number == "5216-0060"
    assert coin.name == "Победоносец"
    assert coin.price == 128980.0
    assert coin.buyout_price is None
    assert coin.weight_g == 7.78
    assert coin.nominal is None
    assert coin.metal is None
    assert coin.purity is None
    assert coin.mintage is None


def test_parse_entity_with_unicode_name(sber_entities):
    coin = parse_entity(sber_entities[2])
    assert coin is not None
    assert coin.catalog_number == "7143-0062"
    assert "Камерун" in coin.name
    assert coin.weight_g == 20.0


def test_parse_entity_falls_back_to_id_for_name():
    coin = parse_entity({"id": "9999-0001", "name": "", "price": None, "mass1": None})
    assert coin is not None
    assert coin.catalog_number == "9999-0001"
    assert coin.name == "9999-0001"
    assert coin.price is None
    assert coin.weight_g is None


def test_parse_entity_prefers_catalog_number_from_api():
    coin = parse_entity(
        {
            "id": "5216-0060",
            "name": "X",
            "catalogNumber": "ЦБ РФ 01-02-03",
            "price": 1.0,
            "mass1": 1.0,
        }
    )
    assert coin is not None
    assert coin.catalog_number == "ЦБ РФ 01-02-03"


def test_parse_entity_returns_none_without_id():
    assert parse_entity({"name": "no-id", "price": 100}) is None
    assert parse_entity({}) is None


def test_parse_entity_to_dict_omits_none():
    coin = parse_entity({"id": "x-1", "name": "X", "price": 10.0, "mass1": None})
    assert coin is not None
    d = coin.to_dict()
    assert d == {
        "url": build_coin_url("x-1"),
        "name": "X",
        "catalog_number": "x-1",
        "price": 10.0,
    }
    assert "weight_g" not in d


def test_parse_entity_buyout_price_from_price_buy():
    coin = parse_entity(
        {
            "id": "z-1",
            "name": "Z",
            "price": 100.0,
            "priceBuy": 77.5,
            "mass1": 1.0,
        }
    )
    assert coin is not None
    assert coin.buyout_price == 77.5
    assert coin.to_dict()["buyout_price"] == 77.5


def test_parse_entity_metal_from_entity():
    coin = parse_entity(
        {
            "id": "m-1",
            "name": "M",
            "metal": "  Серебро  ",
            "price": 10.0,
            "mass1": 31.1,
        }
    )
    assert coin is not None
    assert coin.metal == "Серебро"
    assert coin.to_dict()["metal"] == "Серебро"
