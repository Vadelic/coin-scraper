#!/usr/bin/env python3
"""Скрейпер каталога монет aurumex.ru без внешних библиотек.

Источник: https://aurumex.ru/catalog

Данные отдаются через Nuxt dehydrated payload (JSON-массив с индексными
ссылками). Скрейпим payload для страниц каталога:
- ``/catalog/_payload.json`` (страница 1)
- ``/catalog/page/<N>/_payload.json`` (страницы 2+)

и объединяем монеты (по `url`), пока не перестанут появляться новые.
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import ssl
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen

PUBLIC_URL = "https://aurumex.ru"
CATALOG_PATH = "/catalog"
PAYLOAD_URL = f"{PUBLIC_URL}{CATALOG_PATH}/_payload.json"
PAYLOAD_PAGE_URL_TPL = f"{PUBLIC_URL}{CATALOG_PATH}/page/{{page}}/_payload.json"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_aurumex_catalog.json"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.5

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("aurumex_scraper")


@dataclass
class Coin:
    name: str
    article: str
    url: str
    metal: str | None
    weight_g: float | None
    sample: str | None
    price: float | None
    price_purchase: float | None
    is_available: bool | None

    def to_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v is not None}


def fetch_json(url: str, timeout_s: float, retries: int, delay: float, insecure: bool) -> list:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json, text/plain, */*",
        "Referer": urljoin(PUBLIC_URL, CATALOG_PATH),
    }
    ctx = ssl._create_unverified_context() if insecure else None
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        req = Request(url, headers=headers, method="GET")
        try:
            with urlopen(req, timeout=timeout_s, context=ctx) as resp:
                raw = resp.read().decode("utf-8", errors="ignore")
                return json.loads(raw)
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
            last_error = exc
            log.warning("Попытка %s/%s для %s: %s", attempt, retries, url, exc)
            if attempt < retries:
                time.sleep(delay * (2 ** (attempt - 1)))
    raise RuntimeError(f"Не удалось загрузить {url}: {last_error}")


def deref_cell(store: list, ptr: int | str | float | None | bool):
    """Одношаговое разыменование индекса в dehydrated-массиве Nuxt."""
    if not isinstance(ptr, int):
        return ptr
    if ptr < 0 or ptr >= len(store):
        return ptr
    return store[ptr]


def category_to_metal(slug: str | None) -> str | None:
    if not slug:
        return None
    s = slug.strip().casefold()
    if s == "gold":
        return "золото"
    if s == "silver":
        return "серебро"
    return None


def parse_weight_g(value) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).replace("\u00a0", " ")
    m = re.search(r"\d+(?:[.,]\d+)?", text)
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", "."))
    except ValueError:
        return None


def to_float(value) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).replace("\u00a0", " ").replace(",", ".").replace(" ", "")
    m = re.search(r"\d+(?:\.\d+)?", text)
    if not m:
        return None
    try:
        return float(m.group(0))
    except ValueError:
        return None


def find_catalog_block(store: list) -> dict | None:
    for cell in store:
        if (
            isinstance(cell, dict)
            and "coins" in cell
            and "totalCoins" in cell
            and "categories" in cell
        ):
            return cell
    return None


def extract_coins(store: list) -> tuple[list[Coin], int | None, int | None]:
    block = find_catalog_block(store)
    if not block:
        raise RuntimeError("Не найден блок каталога с ключом coins в payload")

    coins_ptr = block["coins"]
    coins_indices = deref_cell(store, coins_ptr)
    if not isinstance(coins_indices, list):
        raise RuntimeError("coins не является списком")

    # В payload totalCoins совпадает с числом позиций в выдаче (например, в наличии).
    items_in_payload: int | None = None
    total_raw = deref_cell(store, block["totalCoins"])
    if isinstance(total_raw, str) and total_raw.isdigit():
        items_in_payload = int(total_raw)
    elif isinstance(total_raw, int):
        items_in_payload = total_raw

    # Полное число позиций в каталоге (включая «нет в наличии») — в счётчике категории «Все».
    declared_total: int | None = None
    cats_ptr = block.get("categories")
    categories = deref_cell(store, cats_ptr) if isinstance(cats_ptr, int) else None
    if isinstance(categories, list) and categories:
        first_cat = deref_cell(store, categories[0])
        if isinstance(first_cat, dict):
            cnt = first_cat.get("count")
            if isinstance(cnt, int):
                cnt = deref_cell(store, cnt)
            if isinstance(cnt, str) and cnt.isdigit():
                declared_total = int(cnt)
            elif isinstance(cnt, int):
                declared_total = cnt

    coins: list[Coin] = []
    for ref in coins_indices:
        if not isinstance(ref, int):
            continue
        raw = deref_cell(store, ref)
        if not isinstance(raw, dict):
            continue
        try:
            article = str(deref_cell(store, raw["id"]))
            title = deref_cell(store, raw["title"])
            slug_path = deref_cell(store, raw["url"])
            cat = deref_cell(store, raw["category"])
            weight = deref_cell(store, raw["weight"])
            sample = deref_cell(store, raw["sample"])
            price = deref_cell(store, raw["price"])
            price_purchase = deref_cell(store, raw["pricePurchase"])
            is_available = deref_cell(store, raw["isAvailable"])
        except KeyError:
            continue

        if not isinstance(title, str) or not title.strip():
            continue
        url = urljoin(PUBLIC_URL, str(slug_path)) if slug_path else urljoin(PUBLIC_URL, "/")

        coins.append(
            Coin(
                name=title.strip(),
                article=article,
                url=url,
                metal=category_to_metal(cat) if isinstance(cat, str) else None,
                weight_g=parse_weight_g(weight),
                sample=str(sample) if sample is not None else None,
                price=to_float(price),
                price_purchase=to_float(price_purchase),
                is_available=is_available if isinstance(is_available, bool) else None,
            )
        )

    return coins, declared_total, items_in_payload


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет aurumex.ru")
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Путь к JSON")
    p.add_argument(
        "--payload-url",
        default=PAYLOAD_URL,
        help=f"URL Nuxt payload для страницы 1 (по умолчанию {PAYLOAD_URL})",
    )
    p.add_argument("--start-page", type=int, default=1, help="С какой страницы начать (default: 1)")
    p.add_argument(
        "--max-pages",
        type=int,
        default=5,
        help="Максимум страниц для обхода (default: 5). Реальная остановка — раньше, если новые монеты не добавляются.",
    )
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="Таймаут HTTP, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="Повторы запроса")
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="Пауза между retry, с")
    p.add_argument("--insecure", action="store_true", help="Отключить проверку SSL")
    p.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Уровень логирования",
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )

    timeout_s = max(1.0, args.timeout / 1000.0)
    retries = max(1, args.retries)

    start_page = max(1, args.start_page)
    max_pages = max(start_page, args.max_pages)

    log.info("Загрузка payload для страниц aurumex catalog")
    log.info("Page 1 payload: %s", args.payload_url)

    try:
        all_coins_by_url: dict[str, Coin] = {}
        declared_total: int | None = None
        payload_item_count_hint_sum: int = 0
        pages_scraped: int = 0

        for page in range(start_page, max_pages + 1):
            if page == 1:
                url = args.payload_url
            else:
                url = PAYLOAD_PAGE_URL_TPL.format(page=page)

            log.info("Загрузка payload: %s", url)
            store = fetch_json(url, timeout_s, retries, max(0.0, args.delay), args.insecure)
            coins, declared_total_page, items_in_payload = extract_coins(store)

            if declared_total is None and declared_total_page is not None:
                declared_total = declared_total_page

            pages_scraped += 1
            before = len(all_coins_by_url)
            for coin in coins:
                all_coins_by_url.setdefault(coin.url, coin)

            after = len(all_coins_by_url)
            payload_item_count_hint_sum += items_in_payload or 0

            log.info(
                "Page %s: монет в payload=%s, добавлено=%s, всего=%s",
                page,
                len(coins),
                after - before,
                after,
            )

            # Если на следующей странице монеты не добавляются — вероятно, дальше данных нет.
            if after == before and after > 0:
                log.info("Остановка: новые монеты на странице %s не добавились.", page)
                break

        coins = list(all_coins_by_url.values())
    except Exception as exc:
        log.error("%s", exc)
        return 2

    payload = {
        "scraped_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_catalog": urljoin(PUBLIC_URL, CATALOG_PATH),
        "payload_url_page1": args.payload_url,
        "pages_scraped": pages_scraped,
        "payload_url_page_template": PAYLOAD_PAGE_URL_TPL,
        "total_coins": len(coins),
        "declared_catalog_total": declared_total,
        "payload_item_count_hint_sum": payload_item_count_hint_sum,
        "coins": [coin.to_dict() for coin in coins],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log.info(
        "Сохранено монет: %s (объявлено в каталоге: %s). Файл: %s",
        len(coins),
        declared_total,
        args.output,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
