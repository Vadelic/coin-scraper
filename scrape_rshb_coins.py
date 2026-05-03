#!/usr/bin/env python3
"""Скрейпер каталога монет coins.rshb.ru.

Запуск: python3 scrape_rshb_coins.py [options]
Результат: coins_rshb_catalog.json (или путь из --output): у монет есть sku
(артикул из URL), buyout_price при наличии в ответе Elasticsearch каталога
или в тексте карточки.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import sys
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote

from playwright.async_api import (
    Error as PlaywrightError,
    Response,
    Route,
    async_playwright,
)

# ============================================================================
# Constants
# ============================================================================

BASE_URL = "https://coins.rshb.ru"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_rshb_catalog.json"

# Каталог отдаёт по ~24-25 карточек на страницу по умолчанию; page_size=99
# уменьшает число запросов в ~4 раза. При желании заменить через --page-size.
DEFAULT_PAGE_SIZE = 99

# robots.txt: Crawl-delay: 4.5
DEFAULT_PAGE_DELAY = 5.0
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3

CARD_LINK_SELECTOR = "a[href^='/p/']"
PAGINATION_LINK_SELECTOR = "a[href*='page=']"
NEXT_PAGE_SELECTOR = "a[rel='next'], a[aria-label*='Следующая' i]"

BLOCKED_RESOURCE_TYPES = frozenset({"image", "media", "font", "stylesheet"})
ATTRIBUTE_LABELS = (
    "Номинал",
    "Металл",
    "Проба",
    "Чистого металла",
    "Тираж",
    "Выкуп",
    "Цена выкупа",
)
BUYOUT_LABELS = ("Выкуп", "Цена выкупа")

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("rshb_scraper")


# ============================================================================
# Pure functions (no Playwright dependency)
# ============================================================================

def build_url(page: int, page_size: int) -> str:
    """Собирает URL страницы каталога с заданным размером выдачи."""
    return f"{BASE_URL}/?page={page}&page_size={page_size}"


def parse_price(text: str) -> float | None:
    """'109 000 ' -> 109000.0; 'от 109 000,50 ₽' -> 109000.5."""
    if not text:
        return None
    cleaned = text.replace("\u00a0", " ").replace("\u202f", " ")
    m = re.search(r"\d[\d\s]*(?:[.,]\d+)?", cleaned)
    if not m:
        return None
    raw = m.group(0).replace(" ", "").replace(",", ".")
    try:
        return float(raw)
    except ValueError:
        return None


def parse_weight(text: str) -> float | None:
    """Извлекает массу металла в граммах из строки вида '31,1 г'."""
    if not text:
        return None
    m = re.search(r"\b(\d+(?:[,.]\d+)?)\s*г(?:р|рамм)?\b", text)
    return float(m.group(1).replace(",", ".")) if m else None


def _normalize_label(s: str) -> str:
    return s.strip().rstrip(":").casefold()


def extract_labeled_value(text: str, label: str) -> str | None:
    """Извлекает значение, идущее на следующей строке после метки.

    Метка сравнивается без учёта регистра, ведущих/завершающих пробелов и
    завершающего двоеточия.
    """
    target = _normalize_label(label)
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if _normalize_label(line) == target and i + 1 < len(lines):
            value = lines[i + 1].strip()
            if value:
                return value
    return None


def parse_pagination_max(hrefs: Iterable[str]) -> int:
    """Возвращает максимальный page=N из коллекции href-строк (минимум 1)."""
    max_page = 1
    for href in hrefs:
        if not href:
            continue
        m = re.search(r"[?&]page=(\d+)", href)
        if m:
            max_page = max(max_page, int(m.group(1)))
    return max_page


def parse_sku_from_product_href(href: str) -> str | None:
    """Артикул из пути карточки ``/p/<sku>/<slug>`` (после URL-декодирования)."""
    href = (href or "").strip()
    if not href.startswith("/p/"):
        return None
    rest = href[len("/p/") :]
    segment = rest.split("/", 1)[0]
    if not segment:
        return None
    decoded = unquote(segment).strip()
    return decoded or None


def _float_or_none(value) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def buyout_price_from_es_product_dict(d: dict) -> float | None:
    """Цена выкупа из объекта товара в ответе Elasticsearch каталога."""
    if not isinstance(d, dict):
        return None
    for key in (
        "buyout_price",
        "buy_out_price",
        "rshb_buyout_price",
        "price_buy",
        "buyout",
    ):
        v = _float_or_none(d.get(key))
        if v is not None:
            return v
    return None


def register_buyout_hits_from_es_response(
    data: dict,
    registry: dict[str, float],
) -> int:
    """Дополняет registry[sku] ценами выкупа из тела ``_search``; число новых пар."""
    hits = (data or {}).get("hits", {}).get("hits") or []
    added = 0
    for hit in hits:
        src = hit.get("_source")
        if not isinstance(src, dict):
            continue
        children = src.get("configurable_children")
        if isinstance(children, list):
            for ch in children:
                if not isinstance(ch, dict):
                    continue
                sku = ch.get("sku")
                bp = buyout_price_from_es_product_dict(ch)
                if sku is not None and bp is not None:
                    registry[str(sku)] = bp
                    added += 1
        sku = src.get("sku")
        bp = buyout_price_from_es_product_dict(src)
        if sku is not None and bp is not None:
            registry[str(sku)] = bp
            added += 1
    return added


def buyout_price_from_card_text(raw_text: str) -> float | None:
    """Цена выкупа с карточки, если в тексте есть метка «Выкуп» / «Цена выкупа»."""
    for label in BUYOUT_LABELS:
        raw = extract_labeled_value(raw_text, label)
        if raw:
            p = parse_price(raw)
            if p is not None:
                return p
    return None


# ============================================================================
# Coin model
# ============================================================================

@dataclass
class Coin:
    url: str
    name: str
    catalog_number: str | None = None
    sku: str | None = None
    price: float | None = None
    buyout_price: float | None = None
    nominal: str | None = None
    metal: str | None = None
    purity: str | None = None
    weight_g: float | None = None
    mintage: str | None = None

    def to_dict(self) -> dict:
        # Совместимо с предыдущим форматом: ключи с None опускаем.
        return {k: v for k, v in asdict(self).items() if v is not None}


def parse_card_text(
    raw_text: str,
    href: str,
    *,
    buyout_by_sku: dict[str, float] | None = None,
) -> Coin | None:
    """Разбирает inner_text карточки товара в объект Coin.

    ``sku`` — первый сегмент пути ``/p/<sku>/...``. ``buyout_price`` — из словаря
    (ключ = sku, данные из ответа POST ``regional_product/_search``) или с карточки
    по меткам «Выкуп» / «Цена выкупа».
    """
    lines = [l.strip() for l in raw_text.splitlines() if l.strip()]
    if not lines:
        return None

    url = BASE_URL + href
    sku = parse_sku_from_product_href(href)
    buyout_price: float | None = None
    if sku and buyout_by_sku:
        buyout_price = buyout_by_sku.get(sku)
    if buyout_price is None:
        buyout_price = buyout_price_from_card_text(raw_text)

    price = parse_price(lines[0])
    if price is not None and price <= 10:
        # Отсекаем мелкие числа («1 шт», количество отзывов и т.п.).
        price = None

    name = ""
    for line in lines[1:]:
        if len(line) <= 5:
            continue
        if line.replace(" ", "").isdigit():
            continue
        if line in ATTRIBUTE_LABELS:
            continue
        name = line
        break
    if not name:
        name = href.rstrip("/").split("/")[-1]

    weight_raw = extract_labeled_value(raw_text, "Чистого металла")
    weight_g = parse_weight(weight_raw) if weight_raw else None

    return Coin(
        url=url,
        name=name,
        sku=sku,
        price=price,
        buyout_price=buyout_price,
        nominal=extract_labeled_value(raw_text, "Номинал"),
        metal=extract_labeled_value(raw_text, "Металл"),
        purity=extract_labeled_value(raw_text, "Проба"),
        weight_g=weight_g,
        mintage=extract_labeled_value(raw_text, "Тираж"),
    )


# ============================================================================
# Playwright helpers
# ============================================================================

async def block_assets(route: Route) -> None:
    """Прерывает загрузку картинок/шрифтов/стилей — экономит трафик и время."""
    if route.request.resource_type in BLOCKED_RESOURCE_TYPES:
        await route.abort()
    else:
        await route.continue_()


async def load_page(
    page,
    page_num: int,
    *,
    page_size: int,
    timeout_ms: int,
    retries: int,
) -> bool:
    """Загружает страницу с экспоненциальным backoff. True при успехе."""
    url = build_url(page_num, page_size)
    for attempt in range(1, retries + 1):
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            await page.wait_for_selector(CARD_LINK_SELECTOR, timeout=timeout_ms)
            # Каталог дозагружается XHR'ом — даём ему доехать. С блокировкой
            # картинок/шрифтов networkidle обычно наступает за <1 сек.
            try:
                await page.wait_for_load_state("networkidle", timeout=timeout_ms)
            except PlaywrightError:
                log.debug("страница %s: networkidle не наступил — продолжаю", page_num)
            return True
        except PlaywrightError as e:
            log.warning(
                "Страница %s, попытка %s/%s — ошибка: %s",
                page_num, attempt, retries, e,
            )
            if attempt < retries:
                await asyncio.sleep(2 ** attempt)
    return False


async def drain_es_search_responses(
    buffer: list[Response],
    buyout_by_sku: dict[str, float],
) -> int:
    """Разбирает накопленные ответы POST ``.../regional_product/.../_search`` в словарь."""
    pending = buffer[:]
    buffer.clear()
    added = 0
    for response in pending:
        try:
            ct = (response.headers.get("content-type") or "").lower()
            if "json" not in ct:
                continue
            data = await response.json()
        except Exception:
            log.debug("Не удалось прочитать JSON ответа каталога ES", exc_info=True)
            continue
        added += register_buyout_hits_from_es_response(data, buyout_by_sku)
    if added:
        log.info("Из ответов Elasticsearch добавлено пар sku→buyout: %s", added)
    return added


async def get_last_page(page) -> int:
    """Определяет номер последней страницы из ссылок пагинации."""
    links = await page.query_selector_all(PAGINATION_LINK_SELECTOR)
    hrefs: list[str] = []
    for link in links:
        href = await link.get_attribute("href")
        if href:
            hrefs.append(href)
    last = parse_pagination_max(hrefs)
    if last == 1:
        next_btn = await page.query_selector(NEXT_PAGE_SELECTOR)
        if next_btn:
            log.debug(
                "get_last_page: ссылки на последнюю страницу нет, "
                "но есть кнопка «следующая» — пройдём цикл по факту"
            )
    return last


async def extract_coins_from_page(
    page,
    buyout_by_sku: dict[str, float],
) -> list[Coin]:
    """Ищет карточки на странице и парсит их в объекты Coin."""
    coins: list[Coin] = []
    product_links = await page.query_selector_all(CARD_LINK_SELECTOR)
    for link in product_links:
        href = await link.get_attribute("href") or ""
        raw_text = await link.inner_text()
        coin = parse_card_text(raw_text, href, buyout_by_sku=buyout_by_sku)
        if coin is not None:
            coins.append(coin)
    return coins


# ============================================================================
# Orchestration
# ============================================================================

async def scrape_all_pages(args: argparse.Namespace) -> tuple[int, list[Coin]]:
    """Главная функция скрейпинга.

    Возвращает (количество успешно обработанных страниц, список монет).
    """
    all_coins: list[Coin] = []
    seen_urls: set[str] = set()
    buyout_by_sku: dict[str, float] = {}
    es_response_buffer: list[Response] = []

    def on_es_response(response: Response) -> None:
        if response.request.method != "POST":
            return
        url = response.url
        if "regional_product" not in url or "_search" not in url:
            return
        es_response_buffer.append(response)

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(
            headless=not args.headful,
            args=["--no-sandbox", "--disable-setuid-sandbox"],
        )
        context = await browser.new_context(
            user_agent=USER_AGENT,
            locale="ru-RU",
            viewport={"width": 1280, "height": 800},
            extra_http_headers={
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            },
        )
        await context.route("**/*", block_assets)
        page = await context.new_page()
        page.on("response", on_es_response)

        last_page: int | None = None
        n = args.start_page
        processed = 0

        while True:
            if processed > 0:
                await asyncio.sleep(args.delay)

            log.info(
                "Загружаю страницу %s%s",
                n,
                f"/{last_page}" if last_page else "",
            )
            ok = await load_page(
                page, n,
                page_size=args.page_size,
                timeout_ms=args.timeout,
                retries=args.retries,
            )
            if not ok:
                log.error("Страница %s не загрузилась — пропуск", n)
                if processed == 0:
                    # Не смогли загрузить даже первую — выходим.
                    break
                n += 1
                if last_page is not None and n > last_page:
                    break
                continue

            # XHR _search иногда завершается сразу после networkidle — короткая пауза.
            await asyncio.sleep(0.25)
            await drain_es_search_responses(es_response_buffer, buyout_by_sku)

            if last_page is None:
                last_page = await get_last_page(page)
                log.info(
                    "Всего страниц: %s (page_size=%s)",
                    last_page, args.page_size,
                )

            coins = await extract_coins_from_page(page, buyout_by_sku)
            new_coins = [c for c in coins if c.url not in seen_urls]
            for c in new_coins:
                seen_urls.add(c.url)
            all_coins.extend(new_coins)
            processed += 1

            log.info(
                "Страница %s/%s: %s монет (новых: %s, всего: %s)",
                n, last_page, len(coins), len(new_coins), len(all_coins),
            )

            if not coins:
                log.info("Страница %s пустая — остановка", n)
                break

            if args.max_pages is not None and processed >= args.max_pages:
                log.info("Достигнут лимит --max-pages=%s", args.max_pages)
                break
            if last_page is not None and n >= last_page:
                break
            n += 1

        await browser.close()
        return processed, all_coins


# ============================================================================
# CLI
# ============================================================================

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Скрейпер каталога монет coins.rshb.ru",
    )
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                   help="путь к итоговому JSON (default: %(default)s)")
    p.add_argument("--page-size", type=int, default=DEFAULT_PAGE_SIZE,
                   help="параметр page_size в URL (default: %(default)s)")
    p.add_argument("--start-page", type=int, default=1,
                   help="с какой страницы начать (default: %(default)s)")
    p.add_argument("--max-pages", type=int, default=None,
                   help="максимум страниц для обхода (default: все)")
    p.add_argument("--delay", type=float, default=DEFAULT_PAGE_DELAY,
                   help="пауза между страницами, сек (default: %(default)s)")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS,
                   help="таймаут навигации, мс (default: %(default)s)")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES,
                   help="попыток на страницу (default: %(default)s)")
    p.add_argument("--headful", action="store_true",
                   help="показать окно браузера")
    p.add_argument("--log-level", default="INFO",
                   choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                   help="уровень логирования (default: %(default)s)")
    return p


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )


async def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет coins.rshb.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    total_pages, coins = await scrape_all_pages(args)

    result = {
        "scraped_at": started_at.isoformat(),
        "total_pages": total_pages,
        "total_coins": len(coins),
        "coins": [c.to_dict() for c in coins],
    }
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    log.info("=" * 60)
    log.info("  Скачано страниц : %s", total_pages)
    log.info("  Найдено монет   : %s", len(coins))
    log.info("  Результат       : %s", args.output)
    log.info("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
