"""Юнит-тесты чистых функций scrape_rshb_coins (без Playwright)."""
from __future__ import annotations

from pathlib import Path

import pytest

from scrape_rshb_coins import (
    Coin,
    build_url,
    buyout_price_from_card_text,
    buyout_price_from_es_product_dict,
    extract_labeled_value,
    parse_card_text,
    parse_pagination_max,
    parse_price,
    parse_sku_from_product_href,
    parse_weight,
    register_buyout_hits_from_es_response,
)

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# build_url
# ---------------------------------------------------------------------------

def test_build_url_page_size_99():
    assert build_url(3, 99) == "https://coins.rshb.ru/?page=3&page_size=99"


def test_build_url_arbitrary():
    assert build_url(1, 24) == "https://coins.rshb.ru/?page=1&page_size=24"


# ---------------------------------------------------------------------------
# parse_price
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "text, expected",
    [
        ("109 000 ", 109000.0),
        ("от 109 000,50 ₽", 109000.5),
        ("109\u00a0000", 109000.0),
        ("1 шт", 1.0),
        ("", None),
        ("без цифр", None),
    ],
)
def test_parse_price(text, expected):
    assert parse_price(text) == expected


# ---------------------------------------------------------------------------
# parse_weight
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "text, expected",
    [
        ("31,1 г", 31.1),
        ("1.5 гр", 1.5),
        ("100 грамм", 100.0),
        ("график", None),
        ("2014 года выпуска", None),
        ("", None),
    ],
)
def test_parse_weight(text, expected):
    assert parse_weight(text) == expected


# ---------------------------------------------------------------------------
# extract_labeled_value
# ---------------------------------------------------------------------------

def test_extract_labeled_value_basic():
    text = "Номинал\n1000 рублей\nМеталл\nЗолото"
    assert extract_labeled_value(text, "Номинал") == "1000 рублей"
    assert extract_labeled_value(text, "Металл") == "Золото"


def test_extract_labeled_value_normalization_case_and_colon():
    text = "НОМИНАЛ:\n1000 рублей"
    assert extract_labeled_value(text, "Номинал") == "1000 рублей"


def test_extract_labeled_value_missing():
    assert extract_labeled_value("Foo\nBar", "Номинал") is None


def test_extract_labeled_value_empty_value_skipped():
    text = "Номинал\n   \nМеталл\nЗолото"
    assert extract_labeled_value(text, "Номинал") is None
    assert extract_labeled_value(text, "Металл") == "Золото"


# ---------------------------------------------------------------------------
# parse_pagination_max
# ---------------------------------------------------------------------------

def test_parse_pagination_max_basic():
    hrefs = ["/?page=1", "/?foo=bar&page=27", "/?page=3"]
    assert parse_pagination_max(hrefs) == 27


def test_parse_pagination_max_empty_returns_one():
    assert parse_pagination_max([]) == 1


def test_parse_pagination_max_no_match_returns_one():
    assert parse_pagination_max(["/?foo=bar", "/about"]) == 1


def test_parse_pagination_max_skips_blank_strings():
    assert parse_pagination_max(["", "/?page=5"]) == 5


# ---------------------------------------------------------------------------
# sku + buyout (pure)
# ---------------------------------------------------------------------------

def test_parse_sku_from_product_href_decodes_segment():
    assert parse_sku_from_product_href("/p/5216-0060%D1%81/pobeda") == "5216-0060с"
    assert parse_sku_from_product_href("/p/sochi-2014/") == "sochi-2014"
    assert parse_sku_from_product_href("") is None
    assert parse_sku_from_product_href("/about") is None


def test_buyout_price_from_es_product_dict():
    assert buyout_price_from_es_product_dict({"buyout_price": 95000}) == 95000.0
    assert buyout_price_from_es_product_dict({"price_buy": 1.5}) == 1.5
    assert buyout_price_from_es_product_dict({}) is None


def test_register_buyout_hits_from_es_response_parent_and_child():
    reg: dict[str, float] = {}
    data = {
        "hits": {
            "hits": [
                {
                    "_source": {
                        "sku": "parent-1",
                        "configurable_children": [
                            {"sku": "child-a", "buyout_price": 100},
                            {"sku": "child-b", "price": 200},
                        ],
                    }
                }
            ]
        }
    }
    n = register_buyout_hits_from_es_response(data, reg)
    assert n >= 1
    assert reg["child-a"] == 100.0


def test_buyout_price_from_card_text():
    raw = "Цена\n109 000\nВыкуп\n95 000\nМеталл\nЗолото"
    assert buyout_price_from_card_text(raw) == 95000.0


# ---------------------------------------------------------------------------
# parse_card_text + Coin
# ---------------------------------------------------------------------------

def test_parse_card_text_full():
    raw = (FIXTURES / "card.txt").read_text(encoding="utf-8")
    coin = parse_card_text(raw, "/p/sochi-2014/")
    assert coin is not None
    assert coin.url == "https://coins.rshb.ru/p/sochi-2014/"
    assert coin.sku == "sochi-2014"
    assert coin.price == 109000.0
    assert coin.buyout_price is None
    assert "Сочи" in coin.name
    assert coin.nominal == "1000 рублей"
    assert coin.metal == "Золото"
    assert coin.purity == "999"
    assert coin.weight_g == 31.1
    assert coin.mintage == "500"


def test_parse_card_text_buyout_from_map():
    raw = (FIXTURES / "card.txt").read_text(encoding="utf-8")
    coin = parse_card_text(
        raw,
        "/p/sochi-2014/",
        buyout_by_sku={"sochi-2014": 88000.0},
    )
    assert coin is not None
    assert coin.buyout_price == 88000.0


def test_parse_card_text_empty():
    assert parse_card_text("", "/p/x/") is None


def test_parse_card_text_falls_back_to_slug_for_name():
    raw = "1\n2\n3"
    coin = parse_card_text(raw, "/p/coin-slug/")
    assert coin is not None
    assert coin.name == "coin-slug"


def test_coin_to_dict_omits_none_fields():
    c = Coin(url="u", name="n", price=10.0)
    assert c.to_dict() == {"url": "u", "name": "n", "price": 10.0}


def test_coin_to_dict_keeps_zero_and_empty_string():
    c = Coin(url="u", name="", price=0.0)
    d = c.to_dict()
    assert d["price"] == 0.0
    assert d["name"] == ""
    assert "weight_g" not in d
