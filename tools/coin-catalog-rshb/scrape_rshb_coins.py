#!/usr/bin/env python3
"""Скрейпер каталога монет coins.rshb.ru (Россельхозбанк).

Сбор через Playwright. Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
Опционально: --query (?search_text= в URL), --investment-only (?subjects=5506).
Без --with-buy-price: один проход по витрине, sell_price с карточек каталога, buy_price=null.
С --with-buy-price: тот же проход + обогащение buy_price со страницы каждой монеты.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable
from urllib.parse import urlencode, unquote

from playwright.async_api import (
    Error as PlaywrightError,
    Response,
    Route,
    async_playwright,
)

BASE_URL = "https://coins.rshb.ru"
INVESTMENT_SUBJECTS = "5506"
DEFAULT_PAGE_SIZE = 99
DEFAULT_PAGE_DELAY = 5.0
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
# Без x-region сайт отдаёт region=0 — на карточках нет .price-box с ценой.
DEFAULT_REGION_CODE = "77"
REGION_COOKIE_NAME = "x-region"

CARD_LINK_SELECTOR = "a[href^='/p/']"
PAGINATION_LINK_SELECTOR = "a[href*='page=']"
NEXT_PAGE_SELECTOR = "a[rel='next'], a[aria-label*='Следующая' i]"
STOCK_STATUS_MARKERS = (
    "нет в наличии",
    "предзаказ",
    "нет в продаже",
    "распродано",
)
SELL_PRICE_LINE_RE = re.compile(r"(?:₽|руб\.?)", re.IGNORECASE)
SELL_PRICE_ONLY_RE = re.compile(
    r"^\d[\d\s.,]*\s*(?:₽|руб\.?)\s*$",
    re.IGNORECASE,
)
NOMINAL_VALUE_RE = re.compile(
    r"^\d+(?:[.,]\d+)?\s*(?:RUB|RUR|руб\.?)\b",
    re.IGNORECASE,
)
WEIGHT_ONLY_RE = re.compile(
    r"^\d+(?:[.,]\d+)?\s*г(?:р|рамм)?\.?\s*$",
    re.IGNORECASE,
)
NAME_ATTR_CUT_LABELS = ("Номинал", "Металл", "Проба", "Чистого металла", "Тираж", "Выкуп")

# stylesheet не блокируем — иначе цены на карточках могут не отрендериться.
BLOCKED_RESOURCE_TYPES = frozenset({"image", "media", "font"})
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
BUYOUT_PAGE_HINTS = (
    "банк может купить",
    "купить у вас",
    "цене выкупа",
)

BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

METAL_LINE_RE = re.compile(
    r"^(золото|серебро|платина|палладий)\b",
    re.IGNORECASE,
)

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("rshb_scraper")


@dataclass
class RshbCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _url: str | None = None  # для дедупликации, не в JSON

    def to_dict(self) -> dict:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


def build_url(
    page: int,
    page_size: int,
    search_text: str = "",
    *,
    investment_only: bool = False,
) -> str:
    params: dict[str, str | int] = {"page": page, "page_size": page_size}
    q = (search_text or "").strip()
    if q:
        params["search_text"] = q
    if investment_only:
        params["subjects"] = INVESTMENT_SUBJECTS
    return f"{BASE_URL}/?{urlencode(params)}"


def parse_price(text: str) -> float | None:
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
    if not text:
        return None
    m = re.search(r"\b(\d+(?:[,.]\d+)?)\s*г(?:р|рамм)?\b", text)
    return float(m.group(1).replace(",", ".")) if m else None


def normalize_metal(text: str | None) -> str | None:
    if not text:
        return None
    m = METAL_LINE_RE.match(text.strip())
    if m:
        name = m.group(1)
        return name[0].upper() + name[1:].lower()
    return text.strip() or None


def _normalize_label(s: str) -> str:
    return s.strip().rstrip(":").casefold()


def extract_labeled_value(text: str, label: str) -> str | None:
    target = _normalize_label(label)
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if _normalize_label(line) == target and i + 1 < len(lines):
            value = lines[i + 1].strip()
            if value:
                return value
    return None


def parse_pagination_max(hrefs: Iterable[str]) -> int:
    max_page = 1
    for href in hrefs:
        if not href:
            continue
        m = re.search(r"[?&]page=(\d+)", href)
        if m:
            max_page = max(max_page, int(m.group(1)))
    return max_page


def parse_sku_from_product_href(href: str) -> str | None:
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
    if not isinstance(d, dict):
        return None
    ext = d.get("extension_attributes")
    if isinstance(ext, dict):
        bp = buyout_price_from_es_product_dict(ext)
        if bp is not None:
            return bp
    for key in (
        "buyout_price",
        "buy_out_price",
        "rshb_buyout_price",
        "price_buy",
        "buyout",
        "buy_out",
        "buyback_price",
        "buy_back_price",
    ):
        v = _float_or_none(d.get(key))
        if v is not None:
            return v
    return None


def register_buyout_hits_from_es_response(
    data: dict,
    registry: dict[str, float],
    *,
    log_sample: bool = False,
) -> int:
    hits = (data or {}).get("hits", {}).get("hits") or []
    added = 0
    for i, hit in enumerate(hits):
        src = hit.get("_source")
        if not isinstance(src, dict):
            continue
        if log_sample and i == 0:
            log.debug("ES _source keys (sample): %s", sorted(src.keys()))
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
    for label in BUYOUT_LABELS:
        raw = extract_labeled_value(raw_text, label)
        if raw:
            p = parse_price(raw)
            if p is not None:
                return p
    return None


def buyout_price_from_product_page_text(text: str) -> float | None:
    """Цена выкупа со страницы товара («Банк может купить… от N»)."""
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    prices: list[float] = []
    in_section = False
    for line in lines:
        low = line.casefold()
        if any(h in low for h in BUYOUT_PAGE_HINTS):
            in_section = True
            continue
        if not in_section:
            continue
        if "подать заявку" in low or "подробнее о том, как вы можете продать" in low:
            break
        if "от" in low:
            p = parse_price(line)
            if p is not None and p >= 1_000:
                prices.append(p)
    if prices:
        return min(prices)
    return buyout_price_from_card_text(text)


async def fetch_buy_price_from_product_page(
    page,
    product_url: str,
    *,
    timeout_ms: int,
) -> float | None:
    log.info("Открываю карточку: %s", product_url)
    try:
        await page.goto(
            product_url,
            wait_until="domcontentloaded",
            timeout=timeout_ms,
        )
        text = await page.locator("body").inner_text()
        return buyout_price_from_product_page_text(text)
    except PlaywrightError as e:
        log.warning("Выкуп %s: %s", product_url, e)
        return None


async def enrich_buy_prices_from_product_pages(
    page,
    coins: list[RshbCoin],
    *,
    delay: float,
    timeout_ms: int,
) -> None:
    total = len(coins)
    log.info("Загрузка цен выкупа: %s монет", total)
    for i, coin in enumerate(coins, 1):
        if not coin._url:
            log.warning("Выкуп %s/%s: нет URL — «%s»", i, total, coin.name)
            continue
        log.info(
            "Выкуп %s/%s: «%s» (артикул %s)",
            i,
            total,
            coin.name,
            coin.catalog_number,
        )
        coin.buy_price = await fetch_buy_price_from_product_page(
            page, coin._url, timeout_ms=timeout_ms
        )
        log.info(
            "Выкуп %s/%s: buy_price=%s",
            i,
            total,
            coin.buy_price if coin.buy_price is not None else "нет данных",
        )
        if i < total:
            await asyncio.sleep(delay)


def _is_stock_status_line(line: str) -> bool:
    low = line.casefold()
    return any(marker in low for marker in STOCK_STATUS_MARKERS)


def _looks_like_sell_price_line(line: str) -> bool:
    stripped = line.strip()
    if "₽" in stripped:
        if SELL_PRICE_ONLY_RE.match(stripped):
            return True
        if stripped.startswith("₽") or stripped.endswith("₽"):
            return len(stripped) < 30
    return bool(SELL_PRICE_ONLY_RE.match(stripped))


def sell_price_from_price_box(text: str) -> float | None:
    """Цена продажи с карточки каталога (.price-box), обычно «107 000» без ₽."""
    if not (text or "").strip():
        return None
    price = parse_price(text.strip())
    if price is not None and price > 10:
        return price
    return None


def sell_price_from_card_text(raw_text: str) -> float | None:
    lines = [ln.strip() for ln in raw_text.splitlines() if ln.strip()]
    for line in lines:
        if _is_stock_status_line(line):
            continue
        if "₽" not in line and not SELL_PRICE_ONLY_RE.match(line):
            continue
        price = parse_price(line)
        if price is not None and price > 10:
            return price
    return None


def _strip_status_prefix(line: str) -> str:
    low = line.casefold()
    for marker in STOCK_STATUS_MARKERS:
        if low.startswith(marker):
            return line[len(marker) :].strip(" ,—–-")
    return line.strip()


def _is_name_candidate_line(line: str) -> bool:
    if _is_stock_status_line(line):
        return False
    if _looks_like_sell_price_line(line):
        return False
    if NOMINAL_VALUE_RE.match(line):
        return False
    if WEIGHT_ONLY_RE.match(line):
        return False
    if line in ATTRIBUTE_LABELS:
        return False
    if len(line) <= 5:
        return False
    if line.replace(" ", "").isdigit():
        return False
    if METAL_LINE_RE.match(line) and len(line.split()) <= 2:
        return False
    return True


def _name_line_score(line: str) -> int:
    if not _is_name_candidate_line(line):
        return -1
    if re.search(r"[а-яА-ЯёЁa-zA-Z]{4,}", line):
        return 10 + min(len(line), 80)
    return len(line)


def extract_name_from_listing(text: str) -> str:
    """Название с витрины: из текста ссылки, без значений атрибутов (вес, номинал)."""
    if not text:
        return ""
    cut = text
    for label in NAME_ATTR_CUT_LABELS:
        idx = cut.find(label)
        if idx > 0:
            cut = cut[:idx]
    lines = [ln.strip() for ln in cut.splitlines() if ln.strip()]
    if not lines and cut.strip():
        lines = [_strip_status_prefix(cut.strip())]
    candidates: list[str] = []
    for line in lines:
        line = _strip_status_prefix(line)
        if not line:
            continue
        if _is_name_candidate_line(line):
            candidates.append(line)
    if not candidates:
        return ""
    return max(candidates, key=_name_line_score)


def parse_card_text(
    raw_text: str,
    href: str,
    *,
    link_text: str | None = None,
    price_box_text: str | None = None,
) -> RshbCoin | None:
    """Парсинг карточки с витрины каталога: sell_price и атрибуты; buy_price не заполняется."""
    lines = [ln.strip() for ln in raw_text.splitlines() if ln.strip()]
    if not lines:
        return None

    url = BASE_URL + href
    sku = parse_sku_from_product_href(href)
    sell_price = sell_price_from_price_box(price_box_text or "")
    if sell_price is None:
        sell_price = sell_price_from_card_text(raw_text)

    name_source = (link_text or "").strip() or raw_text
    name = extract_name_from_listing(name_source)
    if not name:
        name = href.rstrip("/").split("/")[-1]

    weight_raw = extract_labeled_value(raw_text, "Чистого металла")
    weight_g = parse_weight(weight_raw) if weight_raw else None
    metal_raw = extract_labeled_value(raw_text, "Металл")

    return RshbCoin(
        name=name,
        catalog_number=sku,
        metal=normalize_metal(metal_raw),
        weight_g=weight_g,
        buy_price=None,
        sell_price=sell_price,
        _url=url,
    )


async def launch_chromium_browser(pw, *, headless: bool):
    launch_kwargs: dict = {"headless": headless, "args": LAUNCH_ARGS}
    errors: list[str] = []
    for channel in BROWSER_CHANNELS:
        try:
            browser = await pw.chromium.launch(channel=channel, **launch_kwargs)
            log.info("Браузер: %s", channel)
            return browser
        except PlaywrightError as e:
            errors.append(f"{channel}: {e}")
    try:
        browser = await pw.chromium.launch(**launch_kwargs)
        log.info("Браузер: playwright bundled chromium")
        return browser
    except PlaywrightError as e:
        errors.append(f"bundled: {e}")
    raise PlaywrightError(
        "Не найден браузер для Playwright. Установите Google Chrome или Microsoft Edge. "
        "Опционально: python3 -m playwright install chromium\n" + "\n".join(errors)
    )


async def block_assets(route: Route) -> None:
    if route.request.resource_type in BLOCKED_RESOURCE_TYPES:
        await route.abort()
    else:
        await route.continue_()


async def load_page(
    page,
    page_num: int,
    *,
    page_size: int,
    search_text: str,
    investment_only: bool,
    timeout_ms: int,
    retries: int,
) -> bool:
    url = build_url(page_num, page_size, search_text, investment_only=investment_only)
    if investment_only:
        log.info("Фильтр: только инвестиционные монеты (subjects=%s)", INVESTMENT_SUBJECTS)
    if search_text.strip():
        log.info("Поиск search_text=«%s»", search_text.strip())
    for attempt in range(1, retries + 1):
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            await page.wait_for_selector(CARD_LINK_SELECTOR, timeout=timeout_ms)
            try:
                await page.wait_for_load_state("networkidle", timeout=timeout_ms)
            except PlaywrightError:
                log.debug("страница %s: networkidle не наступил — продолжаю", page_num)
            return True
        except PlaywrightError as e:
            log.warning(
                "Страница %s, попытка %s/%s — ошибка: %s",
                page_num,
                attempt,
                retries,
                e,
            )
            if attempt < retries:
                await asyncio.sleep(2**attempt)
    return False


async def navigate_to_page(
    page,
    page_num: int,
    *,
    page_size: int,
    search_text: str,
    investment_only: bool,
    timeout_ms: int,
    retries: int,
) -> bool:
    """Переход на страницу каталога: клик по пагинации (сохраняет search_text) или goto URL."""
    if page_num <= 1:
        return await load_page(
            page,
            1,
            page_size=page_size,
            search_text=search_text,
            investment_only=investment_only,
            timeout_ms=timeout_ms,
            retries=retries,
        )
    page_link = page.locator(
        f"{PAGINATION_LINK_SELECTOR}[href*='page={page_num}']"
    ).first
    for attempt in range(1, retries + 1):
        try:
            if await page_link.count() > 0:
                await page_link.click()
            else:
                await page.goto(
                    build_url(
                        page_num,
                        page_size,
                        search_text,
                        investment_only=investment_only,
                    ),
                    wait_until="domcontentloaded",
                    timeout=timeout_ms,
                )
            await page.wait_for_selector(CARD_LINK_SELECTOR, timeout=timeout_ms)
            try:
                await page.wait_for_load_state("networkidle", timeout=timeout_ms)
            except PlaywrightError:
                log.debug("страница %s: networkidle не наступил — продолжаю", page_num)
            return True
        except PlaywrightError as e:
            log.warning(
                "Страница %s, попытка %s/%s — ошибка: %s",
                page_num,
                attempt,
                retries,
                e,
            )
            if attempt < retries:
                await asyncio.sleep(2**attempt)
    return False


async def drain_es_search_responses(
    buffer: list[Response],
    buyout_by_sku: dict[str, float],
) -> int:
    pending = buffer[:]
    buffer.clear()
    added = 0
    logged_sample = False
    for response in pending:
        try:
            ct = (response.headers.get("content-type") or "").lower()
            if "json" not in ct:
                continue
            data = await response.json()
        except Exception:
            log.debug("Не удалось прочитать JSON ответа каталога ES", exc_info=True)
            continue
        added += register_buyout_hits_from_es_response(
            data, buyout_by_sku, log_sample=not logged_sample
        )
        if not logged_sample:
            logged_sample = True
    if added:
        log.info("Из ответов Elasticsearch добавлено пар sku→buyout: %s", added)
    return added


async def get_last_page(page) -> int:
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
            log.debug("get_last_page: есть кнопка «следующая» — обход по факту")
    return last


_CARD_TEXT_JS = """el => {
  const wrapper = el.closest('.product-wrapper');
  const root = wrapper || el.closest(
    'article, li, [class*="product"], [class*="card"], [class*="item"]'
  ) || el.parentElement;
  const priceEl = (wrapper || root || el).querySelector('.price-box');
  return {
    card: (root || el).innerText || el.innerText || '',
    link: el.innerText || '',
    priceBox: (priceEl && priceEl.innerText) ? priceEl.innerText.trim() : '',
  };
}"""


async def extract_coins_from_page(page) -> list[RshbCoin]:
    """Один проход по витрине: цены продажи и атрибуты с карточек каталога."""
    coins: list[RshbCoin] = []
    for link in await page.query_selector_all(CARD_LINK_SELECTOR):
        href = await link.get_attribute("href") or ""
        try:
            block = await link.evaluate(_CARD_TEXT_JS)
            raw_text = block.get("card") or block.get("link") or ""
            link_text = block.get("link") or ""
            price_box_text = block.get("priceBox") or ""
        except PlaywrightError:
            raw_text = await link.inner_text()
            link_text = raw_text
            price_box_text = ""
        coin = parse_card_text(
            raw_text,
            href,
            link_text=link_text,
            price_box_text=price_box_text,
        )
        if coin is None:
            log.warning("Пропуск карточки: не удалось распарсить href=%s", href)
            continue
        if not coin.name:
            log.warning("Пропуск карточки: пустое название, url=%s", coin._url)
            continue
        coins.append(coin)
    return coins


async def scrape_all_pages(args: argparse.Namespace) -> tuple[int, list[RshbCoin]]:
    all_coins: list[RshbCoin] = []
    seen_urls: set[str] = set()
    url_query = (args.query or "").strip()
    investment_only = bool(args.investment_only)

    async with async_playwright() as pw:
        browser = await launch_chromium_browser(pw, headless=not args.headful)
        region = (args.region or DEFAULT_REGION_CODE).strip()
        context = await browser.new_context(
            user_agent=USER_AGENT,
            locale="ru-RU",
            viewport={"width": 1280, "height": 800},
            extra_http_headers={
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            },
        )
        await context.add_cookies(
            [
                {
                    "name": REGION_COOKIE_NAME,
                    "value": region,
                    "domain": "coins.rshb.ru",
                    "path": "/",
                }
            ]
        )
        log.info("Регион каталога: %s=%s", REGION_COOKIE_NAME, region)
        await context.route("**/*", block_assets)
        page = await context.new_page()

        last_page: int | None = None
        n = args.start_page
        processed = 0
        coin_counter = 0

        while True:
            if processed > 0:
                await asyncio.sleep(args.delay)

            log.info(
                "Загружаю страницу %s%s",
                n,
                f"/{last_page}" if last_page else "",
            )
            if processed == 0 and n == args.start_page:
                ok = await load_page(
                    page,
                    n,
                    page_size=args.page_size,
                    search_text=url_query,
                    investment_only=investment_only,
                    timeout_ms=args.timeout,
                    retries=args.retries,
                )
            else:
                ok = await navigate_to_page(
                    page,
                    n,
                    page_size=args.page_size,
                    search_text=url_query,
                    investment_only=investment_only,
                    timeout_ms=args.timeout,
                    retries=args.retries,
                )
            if not ok:
                log.error("Страница %s не загрузилась — пропуск", n)
                if processed == 0:
                    break
                n += 1
                if last_page is not None and n > last_page:
                    break
                continue

            await asyncio.sleep(0.25)

            if last_page is None:
                last_page = await get_last_page(page)
                log.info("Всего страниц: %s (page_size=%s)", last_page, args.page_size)

            coins = await extract_coins_from_page(page)
            new_coins: list[RshbCoin] = []
            for c in coins:
                key = c._url or ""
                if key in seen_urls:
                    log.warning(
                        "Дубликат карточки (url %s): «%s», артикул=%s",
                        key,
                        c.name,
                        c.catalog_number,
                    )
                    continue
                seen_urls.add(key)
                new_coins.append(c)
                coin_counter += 1
                if coin_counter % 10 == 0:
                    log.info(
                        "Монета %s: «%s» (артикул %s)",
                        coin_counter,
                        c.name,
                        c.catalog_number,
                    )
                if c.sell_price is None:
                    log.warning(
                        "Нет sell_price для '%s' (артикул %s)",
                        c.name,
                        c.catalog_number,
                    )
            all_coins.extend(new_coins)
            processed += 1

            log.info(
                "Страница %s/%s: %s монет (новых: %s, всего: %s)",
                n,
                last_page,
                len(coins),
                len(new_coins),
                len(all_coins),
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

        if args.with_buy_price and all_coins:
            log.info(
                "Каталог собран (%s монет), обогащение buy_price со страниц товаров",
                len(all_coins),
            )
            await enrich_buy_prices_from_product_pages(
                page,
                all_coins,
                delay=args.delay,
                timeout_ms=args.timeout,
            )

        await browser.close()
        return processed, all_coins


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет coins.rshb.ru")
    p.add_argument(
        "--query",
        default="",
        help="строка поиска (?search_text= в URL каталога; пусто — без фильтра)",
    )
    p.add_argument(
        "--investment-only",
        action="store_true",
        help=f"только инвестиционные монеты (?subjects={INVESTMENT_SUBJECTS} в URL каталога)",
    )
    p.add_argument("--page-size", type=int, default=DEFAULT_PAGE_SIZE)
    p.add_argument("--start-page", type=int, default=1)
    p.add_argument("--max-pages", type=int, default=None)
    p.add_argument("--delay", type=float, default=DEFAULT_PAGE_DELAY)
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS)
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES)
    p.add_argument("--headful", action="store_true")
    p.add_argument(
        "--with-buy-price",
        action="store_true",
        help="после витрины открыть каждую монету и дополнить buy_price (медленно; удобно с --query)",
    )
    p.add_argument(
        "--region",
        default=DEFAULT_REGION_CODE,
        help=f"код региона для cookie {REGION_COOKIE_NAME} (по умолчанию {DEFAULT_REGION_CODE}, Москва)",
    )
    p.add_argument(
        "--log-level",
        default="DEBUG",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    return p


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stderr,
    )


async def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет coins.rshb.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[RshbCoin] = []

    try:
        total_pages, coins = await scrape_all_pages(args)
    except PlaywrightError as e:
        scrape_status = "error"
        error_message = str(e)
        log.error("Ошибка Playwright: %s", e)

    result = {
        "scraped_at": started_at.isoformat(),
        "scrape_status": scrape_status,
        "total_pages": total_pages,
        "total_coins": len(coins),
        "coins": [c.to_dict() for c in coins],
    }
    if args.query and args.query.strip():
        result["query"] = args.query.strip()
    if args.investment_only:
        result["investment_only"] = True
    if error_message:
        result["error"] = error_message

    sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

    log.info("=" * 60)
    log.info("  Скачано страниц : %s", total_pages)
    log.info("  Найдено монет   : %s", len(coins))
    log.info("  Статус          : %s", scrape_status)
    log.info("=" * 60)
    return 0 if scrape_status == "ok" else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
