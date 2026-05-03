#!/usr/bin/env python3
"""Скрейпер каталога монет lanta.ru (Санкт-Петербург).

Источник: https://www.lanta.ru/petersburg/metals/coins/
Сбор через Playwright (антибот/CAPTCHA и динамический рендер).

Итог: coins_lanta_catalog.json
Поля монеты: name, catalog_number, metal, weight_g, buy_price, sell_price, url.
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
from urllib.parse import urljoin

from playwright.async_api import Error as PlaywrightError, Route, async_playwright

# ============================================================================
# Constants
# ============================================================================

BASE_URL = "https://www.lanta.ru"
CATALOG_URL = "https://www.lanta.ru/petersburg/metals/coins/"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_lanta_catalog.json"

DEFAULT_DELAY = 0.5
DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3
DEFAULT_SCROLL_PASSES = 8

BLOCKED_RESOURCE_TYPES = frozenset({"image", "media", "font", "stylesheet"})
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

BUY_LABELS = ("покупка", "купим", "покупаем", "buy")
SELL_LABELS = ("продажа", "продаем", "продаём", "sell")
CATALOG_NUMBER_LABELS = ("каталожный номер", "артикул", "номер")
METAL_LABELS = ("металл",)
WEIGHT_LABELS = ("вес", "масса")

CARD_SELECTORS = (
    ".coins-item",
    ".coin-item",
    ".product-item",
    ".catalog-item",
    ".goods-item",
    "[class*='coin']",
    "table tbody tr",
)

log = logging.getLogger("lanta_scraper")


# ============================================================================
# Model
# ============================================================================


@dataclass
class LantaCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    url: str | None = None

    def to_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v is not None}


# ============================================================================
# Pure helpers
# ============================================================================


def parse_price(text: str) -> float | None:
    """'1 234 567 ₽' -> 1234567.0."""
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


def parse_weight_g(text: str) -> float | None:
    """'7,78 г' -> 7.78."""
    if not text:
        return None
    m = re.search(r"\b(\d+(?:[,.]\d+)?)\s*г(?:р|рамм)?\b", text.casefold())
    return float(m.group(1).replace(",", ".")) if m else None


def _normalize(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).casefold()


def extract_labeled_value(text: str, labels: tuple[str, ...]) -> str | None:
    """Ищет значение на строке после метки из labels."""
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    for i, line in enumerate(lines):
        n = _normalize(line).rstrip(":")
        if any(n == _normalize(lbl) for lbl in labels):
            if i + 1 < len(lines):
                value = lines[i + 1].strip()
                if value:
                    return value
    return None


def extract_price_pair(text: str) -> tuple[float | None, float | None]:
    """Возвращает (buy_price, sell_price)."""
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    buy_price: float | None = None
    sell_price: float | None = None
    for i, line in enumerate(lines):
        norm = _normalize(line)
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        if any(lbl in norm for lbl in BUY_LABELS):
            p = parse_price(nxt) or parse_price(line)
            if p is not None:
                buy_price = p
        if any(lbl in norm for lbl in SELL_LABELS):
            p = parse_price(nxt) or parse_price(line)
            if p is not None:
                sell_price = p

    # Общая метка "Цена" без явного buy/sell.
    if buy_price is None and sell_price is None:
        for i, line in enumerate(lines):
            norm = _normalize(line)
            if "цена" not in norm:
                continue
            nxt = lines[i + 1] if i + 1 < len(lines) else ""
            p = parse_price(nxt) or parse_price(line)
            if p is not None:
                sell_price = p
                return buy_price, sell_price

    if buy_price is not None or sell_price is not None:
        return buy_price, sell_price

    # Явных меток нет: берем 1-2 строки, похожие именно на цены.
    prices: list[float] = []
    for line in lines:
        norm = _normalize(line)
        if not any(token in norm for token in ("₽", "руб", "usd", "$", "eur", "€")):
            continue
        p = parse_price(line)
        if p is not None:
            prices.append(p)
    if len(prices) >= 2:
        return prices[0], prices[1]
    if len(prices) == 1:
        # Неоднозначно: по умолчанию считаем это ценой продажи.
        return None, prices[0]
    return None, None


def parse_lanta_card_text(raw_text: str, url: str | None = None) -> LantaCoin | None:
    """Парсит текст карточки в LantaCoin."""
    lines = [ln.strip() for ln in raw_text.splitlines() if ln.strip()]
    if not lines:
        return None

    name = ""
    for line in lines:
        n = _normalize(line)
        if any(lbl in n for lbl in BUY_LABELS + SELL_LABELS):
            continue
        if parse_price(line) is not None:
            continue
        if len(line) < 3:
            continue
        name = line
        break
    if not name:
        return None

    catalog_number = extract_labeled_value(raw_text, CATALOG_NUMBER_LABELS)
    metal = extract_labeled_value(raw_text, METAL_LABELS)
    weight_raw = extract_labeled_value(raw_text, WEIGHT_LABELS)
    weight_g = parse_weight_g(weight_raw or "")
    buy_price, sell_price = extract_price_pair(raw_text)

    return LantaCoin(
        name=name,
        catalog_number=catalog_number,
        metal=metal,
        weight_g=weight_g,
        buy_price=buy_price,
        sell_price=sell_price,
        url=url,
    )


# ============================================================================
# Playwright helpers
# ============================================================================


async def block_assets(route: Route) -> None:
    """Блокирует тяжелые ресурсы для ускорения."""
    if route.request.resource_type in BLOCKED_RESOURCE_TYPES:
        await route.abort()
    else:
        await route.continue_()


async def scroll_until_stable(page, passes: int, delay: float) -> None:
    """Прокручивает страницу, пока количество карточек стабильно."""
    js_count = """
() => {
  const selectors = %s;
  let count = 0;
  for (const sel of selectors) {
    count = Math.max(count, document.querySelectorAll(sel).length);
  }
  return count;
}
""" % (json.dumps(list(CARD_SELECTORS)))

    stable = 0
    prev = -1
    for _ in range(max(1, passes)):
        await page.mouse.wheel(0, 3000)
        await asyncio.sleep(delay)
        cur = await page.evaluate(js_count)
        if cur == prev:
            stable += 1
        else:
            stable = 0
        prev = cur
        if stable >= 2:
            break


async def collect_raw_cards(page) -> list[tuple[str, str | None]]:
    """Собирает raw_text и ссылку каждой карточки."""
    js = """
() => {
  const sels = %s;
  const seen = new Set();
  const out = [];
  for (const sel of sels) {
    for (const el of document.querySelectorAll(sel)) {
      const text = (el.innerText || '').trim();
      if (!text) continue;
      const key = text.slice(0, 250);
      if (seen.has(key)) continue;
      seen.add(key);
      const a = el.closest('a[href]') || el.querySelector('a[href]');
      out.push({ text, href: a ? a.getAttribute('href') : null });
    }
  }
  return out;
}
""" % (json.dumps(list(CARD_SELECTORS)))
    rows = await page.evaluate(js)
    result: list[tuple[str, str | None]] = []
    for row in rows:
        text = (row.get("text") or "").strip()
        href = row.get("href")
        result.append((text, href if isinstance(href, str) else None))
    return result


# ============================================================================
# Orchestration
# ============================================================================


async def scrape(args: argparse.Namespace) -> list[LantaCoin]:
    coins: list[LantaCoin] = []
    seen_keys: set[str] = set()

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(
            headless=not args.headful,
            args=["--no-sandbox", "--disable-setuid-sandbox"],
        )
        context = await browser.new_context(
            user_agent=USER_AGENT,
            locale="ru-RU",
            viewport={"width": 1366, "height": 900},
            extra_http_headers={
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            },
        )
        await context.route("**/*", block_assets)
        page = await context.new_page()

        try:
            for attempt in range(1, args.retries + 1):
                try:
                    log.info("Открываю %s (попытка %s/%s)", CATALOG_URL, attempt, args.retries)
                    await page.goto(
                        CATALOG_URL,
                        wait_until="domcontentloaded",
                        timeout=args.timeout,
                    )
                    try:
                        await page.wait_for_load_state("networkidle", timeout=args.timeout)
                    except PlaywrightError:
                        log.debug("networkidle не наступил — продолжаю")
                    break
                except PlaywrightError as e:
                    log.warning("Навигация: попытка %s/%s — %s", attempt, args.retries, e)
                    if attempt < args.retries:
                        await asyncio.sleep(2 ** attempt)
            else:
                log.error("Не удалось открыть страницу каталога")
                return []

            await scroll_until_stable(page, args.scroll_passes, args.delay)
            raw_cards = await collect_raw_cards(page)
            log.info("Найдено raw-карточек: %s", len(raw_cards))

            for raw_text, href in raw_cards:
                absolute_url = urljoin(CATALOG_URL, href) if href else None
                coin = parse_lanta_card_text(raw_text, absolute_url)
                if coin is None:
                    continue
                key = f"{coin.catalog_number or ''}|{coin.name}|{coin.weight_g or ''}"
                if key in seen_keys:
                    continue
                seen_keys.add(key)
                if coin.buy_price is None or coin.sell_price is None:
                    log.warning(
                        "Неполные цены для '%s': buy=%s sell=%s",
                        coin.name,
                        coin.buy_price,
                        coin.sell_price,
                    )
                coins.append(coin)
                if args.max_items is not None and len(coins) >= args.max_items:
                    break
        finally:
            await browser.close()

    return coins


# ============================================================================
# CLI
# ============================================================================


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет lanta.ru")
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="путь к итоговому JSON")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="таймаут, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="попыток навигации")
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="пауза между действиями, с")
    p.add_argument(
        "--scroll-passes",
        type=int,
        default=DEFAULT_SCROLL_PASSES,
        help="макс. число прокруток для lazy-load",
    )
    p.add_argument("--max-items", type=int, default=None, help="ограничить число монет")
    p.add_argument("--headful", action="store_true", help="показать окно браузера")
    p.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="уровень логирования",
    )
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
    log.info("  Скрейпер каталога монет lanta.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    coins = await scrape(args)

    result = {
        "scraped_at": started_at.isoformat(),
        "total_pages": 1,
        "total_coins": len(coins),
        "coins": [c.to_dict() for c in coins],
    }
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    log.info("=" * 60)
    log.info("  Найдено монет : %s", len(coins))
    log.info("  Результат     : %s", args.output)
    log.info("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
