#!/usr/bin/env python3
"""Скрейпер каталога монет coins.rshb.ru (Россельхозбанк).

Сбор через Playwright. Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
Опционально: --query — поиск через ?search_text= в URL каталога.
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
DEFAULT_PAGE_SIZE = 99
DEFAULT_PAGE_DELAY = 5.0
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3

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
NOMINAL_VALUE_RE = re.compile(r"^\d+\s*RUB\b", re.IGNORECASE)

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


def build_url(page: int, page_size: int, search_text: str = "") -> str:
    params: dict[str, str | int] = {"page": page, "page_size": page_size}
    q = (search_text or "").strip()
    if q:
        params["search_text"] = q
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


def _is_stock_status_line(line: str) -> bool:
    low = line.casefold()
    return any(marker in low for marker in STOCK_STATUS_MARKERS)


def sell_price_from_card_text(raw_text: str) -> float | None:
    lines = [ln.strip() for ln in raw_text.splitlines() if ln.strip()]
    for line in lines:
        if _is_stock_status_line(line):
            continue
        if not SELL_PRICE_LINE_RE.search(line):
            continue
        price = parse_price(line)
        if price is not None and price > 10:
            return price
    return None


def _is_name_candidate_line(line: str) -> bool:
    if _is_stock_status_line(line):
        return False
    if SELL_PRICE_LINE_RE.search(line):
        return False
    if NOMINAL_VALUE_RE.match(line):
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


def parse_card_text(
    raw_text: str,
    href: str,
    *,
    buyout_by_sku: dict[str, float] | None = None,
) -> RshbCoin | None:
    lines = [ln.strip() for ln in raw_text.splitlines() if ln.strip()]
    if not lines:
        return None

    url = BASE_URL + href
    sku = parse_sku_from_product_href(href)
    buy_price: float | None = None
    if sku and buyout_by_sku:
        buy_price = buyout_by_sku.get(sku)
    if buy_price is None:
        buy_price = buyout_price_from_card_text(raw_text)

    sell_price = sell_price_from_card_text(raw_text)

    name = ""
    for line in lines:
        if _is_name_candidate_line(line):
            name = line
            break
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
        buy_price=buy_price,
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
    timeout_ms: int,
    retries: int,
) -> bool:
    url = build_url(page_num, page_size, search_text)
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
                    build_url(page_num, page_size, search_text),
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
  const root = el.closest(
    'article, li, [class*="product"], [class*="card"], [class*="item"]'
  ) || el.parentElement;
  return (root || el).innerText || el.innerText;
}"""


async def extract_coins_from_page(
    page,
    buyout_by_sku: dict[str, float],
) -> list[RshbCoin]:
    coins: list[RshbCoin] = []
    for link in await page.query_selector_all(CARD_LINK_SELECTOR):
        href = await link.get_attribute("href") or ""
        try:
            raw_text = await link.evaluate(_CARD_TEXT_JS)
        except PlaywrightError:
            raw_text = await link.inner_text()
        coin = parse_card_text(raw_text, href, buyout_by_sku=buyout_by_sku)
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
    buyout_by_sku: dict[str, float] = {}
    es_response_buffer: list[Response] = []
    url_query = (args.query or "").strip()

    def on_es_response(response: Response) -> None:
        if response.request.method != "POST":
            return
        url = response.url
        if "_search" not in url or "coins.rshb.ru" not in url:
            return
        es_response_buffer.append(response)

    async with async_playwright() as pw:
        browser = await launch_chromium_browser(pw, headless=not args.headful)
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
                    timeout_ms=args.timeout,
                    retries=args.retries,
                )
            else:
                ok = await navigate_to_page(
                    page,
                    n,
                    page_size=args.page_size,
                    search_text=url_query,
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
            await drain_es_search_responses(es_response_buffer, buyout_by_sku)

            if last_page is None:
                last_page = await get_last_page(page)
                log.info("Всего страниц: %s (page_size=%s)", last_page, args.page_size)

            coins = await extract_coins_from_page(page, buyout_by_sku)
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
                if c.buy_price is None and c.sell_price is None:
                    log.warning(
                        "Нет цен для '%s' (артикул %s)",
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

        await browser.close()
        return processed, all_coins


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет coins.rshb.ru")
    p.add_argument(
        "--query",
        default="",
        help="строка поиска (?search_text= в URL каталога; пусто — без фильтра)",
    )
    p.add_argument("--page-size", type=int, default=DEFAULT_PAGE_SIZE)
    p.add_argument("--start-page", type=int, default=1)
    p.add_argument("--max-pages", type=int, default=None)
    p.add_argument("--delay", type=float, default=DEFAULT_PAGE_DELAY)
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS)
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES)
    p.add_argument("--headful", action="store_true")
    p.add_argument(
        "--log-level",
        default="INFO",
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
