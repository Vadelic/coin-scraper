"""Юнит-тесты чистых функций scrape_lanta_coins (без Playwright)."""
from __future__ import annotations

from pathlib import Path

import pytest

from scrape_lanta_coins import (
    extract_labeled_value,
    extract_price_pair,
    parse_lanta_card_text,
    parse_price,
    parse_weight_g,
)

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.mark.parametrize(
    "text, expected",
    [
        ("1 234 567 ₽", 1234567.0),
        ("95 000", 95000.0),
        ("", None),
        ("нет цены", None),
    ],
)
def test_parse_price(text, expected):
    assert parse_price(text) == expected


@pytest.mark.parametrize(
    "text, expected",
    [
        ("7,78 г", 7.78),
        ("31.1 гр", 31.1),
        ("без веса", None),
    ],
)
def test_parse_weight_g(text, expected):
    assert parse_weight_g(text) == expected


def test_extract_labeled_value():
    text = "Каталожный номер\n5216-0060\nМеталл\nЗолото"
    assert extract_labeled_value(text, ("Каталожный номер",)) == "5216-0060"
    assert extract_labeled_value(text, ("Металл",)) == "Золото"
    assert extract_labeled_value(text, ("Нет",)) is None


def test_extract_price_pair_two_prices_with_labels():
    text = (FIXTURES / "lanta_card_two_prices.txt").read_text(encoding="utf-8")
    buy, sell = extract_price_pair(text)
    assert buy == 95000.0
    assert sell == 129900.0


def test_extract_price_pair_single_price_goes_to_sell():
    text = (FIXTURES / "lanta_card_one_price.txt").read_text(encoding="utf-8")
    buy, sell = extract_price_pair(text)
    assert buy is None
    assert sell == 18500.0


def test_parse_lanta_card_text_full():
    text = (FIXTURES / "lanta_card_two_prices.txt").read_text(encoding="utf-8")
    coin = parse_lanta_card_text(text, "https://www.lanta.ru/petersburg/metals/coins/x")
    assert coin is not None
    assert coin.name == "Монета Георгий Победоносец"
    assert coin.catalog_number == "5216-0060"
    assert coin.metal == "Золото"
    assert coin.weight_g == 7.78
    assert coin.buy_price == 95000.0
    assert coin.sell_price == 129900.0
    assert coin.url and "lanta.ru" in coin.url


def test_parse_lanta_card_text_one_price():
    text = (FIXTURES / "lanta_card_one_price.txt").read_text(encoding="utf-8")
    coin = parse_lanta_card_text(text)
    assert coin is not None
    assert coin.catalog_number == "1111-2222"
    assert coin.metal == "Серебро"
    assert coin.weight_g == 31.1
    assert coin.buy_price is None
    assert coin.sell_price == 18500.0
