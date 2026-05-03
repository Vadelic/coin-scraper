#!/usr/bin/env python3
"""Скрейпер каталога монет ВТБ через BFF API.

POST https://www.vtb.ru/api/bff/api/v1/coin/list?page=N с телом {"filters":[]}.
В JSON: вес, металл, артикул (article), название, ссылка на витрину,
номинал и цена (price1).

Запуск: python3 scrape_vtb_coins.py [options]
Результат: coins_vtb_catalog.json (или путь из --output).

Скрейпер работает через Playwright (без дополнительных HTTP-библиотек).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    from playwright.async_api import Error as PlaywrightError, async_playwright
except ModuleNotFoundError:
    PlaywrightError = Exception
    async_playwright = None

# ============================================================================
# Constants
# ============================================================================

BASE_SITE = "https://www.vtb.ru"
LIST_PATH = "/api/bff/api/v1/coin/list"
COIN_CATALOG_URL = (
    f"{BASE_SITE}/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/"
)

DEFAULT_OUTPUT = Path(__file__).parent / "coins_vtb_catalog.json"
DEFAULT_TIMEOUT = 30.0
DEFAULT_RETRIES = 3
DEFAULT_PAGE_DELAY = 0.35
DEFAULT_TIMEOUT_MS = 45_000

DEFAULT_HEADERS = {
    "Content-Type": "application/json",
    "Accept": "application/json",
    "Origin": BASE_SITE,
    "Referer": f"{BASE_SITE}/",
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
}

log = logging.getLogger("vtb_scraper")

_FETCH_JS = """
async (args) => {
    const r = await fetch(args.url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
        body: JSON.stringify(args.body),
        credentials: 'include',
    });
    const ct = r.headers.get('content-type') || '';
    if (!r.ok) {
        const text = await r.text();
        throw new Error('HTTP ' + r.status + ': ' + text.slice(0, 200));
    }
    if (!ct.includes('application/json')) {
        const text = await r.text();
        throw new Error('Non-JSON (' + ct + '): ' + text.slice(0, 200));
    }
    return await r.json();
}
"""


# ============================================================================
# Pure helpers
# ============================================================================


def build_list_url(page: int) -> str:
    """URL страницы списка монет (query page — с 1)."""
    return f"{BASE_SITE}{LIST_PATH}?page={int(page)}"


def build_payload(filters: list[Any] | None = None) -> dict:
    """Тело POST для /coin/list."""
    return {"filters": list(filters) if filters else []}


def parse_list_response(body: dict) -> tuple[list[dict], int]:
    """Извлекает список монет и maxPage из JSON ответа BFF."""
    if not isinstance(body, dict):
        return [], 1
    coins = body.get("coins")
    if not isinstance(coins, list):
        coins = []
    try:
        max_page = max(1, int(body.get("maxPage") or 1))
    except (TypeError, ValueError):
        max_page = 1
    return coins, max_page


def build_coin_url(*, article: str | None, coin_id: str | None) -> str:
    """Ссылка на витрину монет с параметрами для поиска карточки (article, id)."""
    base = COIN_CATALOG_URL.rstrip("/") + "/"
    parts: list[str] = []
    if article:
        parts.append(f"article={article}")
    if coin_id:
        parts.append(f"id={coin_id}")
    if parts:
        return base + "?" + "&".join(parts)
    return base


def dedupe_key(row: dict, flat: dict[str, Any]) -> str:
    """Ключ дедупликации: UUID монеты из API, иначе артикул или URL."""
    rid = row.get("id")
    if rid is not None and str(rid).strip():
        return str(rid).strip()
    if flat.get("article"):
        return str(flat["article"])
    return str(flat.get("url") or "")


def coin_to_output(item: dict) -> dict[str, Any] | None:
    """Преобразует элемент ``coins[]`` в плоский dict для итогового JSON."""
    if not isinstance(item, dict):
        return None
    name = (item.get("name") or "").strip()
    raw_article = item.get("article")
    article = str(raw_article).strip() if raw_article is not None else ""
    if not name and not article:
        return None

    mass = item.get("mass")
    weight_g: float | None
    try:
        weight_g = float(mass) if mass is not None else None
    except (TypeError, ValueError):
        weight_g = None

    metal = (item.get("metal") or "").strip() or None

    nv = item.get("nominalValue")
    nc = (item.get("nominalCurrency") or "").strip()
    nominal_parts: list[str] = []
    if nv is not None and str(nv).strip():
        nominal_parts.append(str(nv).strip())
    if nc:
        nominal_parts.append(nc)
    nominal = " ".join(nominal_parts).strip() or None

    cid = item.get("id")
    coin_id = str(cid).strip() if cid is not None else None
    url = build_coin_url(article=article or None, coin_id=coin_id)
    p1 = item.get("price1")
    try:
        price1 = float(p1) if p1 is not None else None
    except (TypeError, ValueError):
        price1 = None

    out: dict[str, Any] = {
        "name": name or None,
        "url": url,
        "nominal": nominal,
        "metal": metal,
        "weight_g": weight_g,
        "price1": price1,
    }
    if article:
        out["article"] = article
    return {k: v for k, v in out.items() if v is not None}


# ============================================================================
# Fetch
# ============================================================================


async def fetch_list_page_playwright(
    page,
    page_num: int,
    *,
    timeout_ms: int,
    retries: int,
) -> dict | None:
    """Загрузка одной страницы списка из контекста браузера с повторами."""
    url = build_list_url(page_num)
    payload = build_payload()
    for attempt in range(1, retries + 1):
        try:
            data = await page.evaluate(
                _FETCH_JS,
                {"url": url, "body": payload},
            )
            return data if isinstance(data, dict) else None
        except (PlaywrightError, Exception) as e:
            log.warning(
                "Playwright: страница %s, попытка %s/%s — %s",
                page_num,
                attempt,
                retries,
                e,
            )
            if attempt < retries:
                await asyncio.sleep(2 ** attempt)
    return None



async def scrape_vtb_via_playwright(
    args: argparse.Namespace,
    seen: set[str],
    merged: list[dict[str, Any]],
    max_page_seen: int,
) -> tuple[int, list[dict[str, Any]]]:
    """Резервный режим: загрузка через браузерный fetch из контекста vtb.ru."""
    if async_playwright is None:
        raise RuntimeError(
            "Не найдены зависимости для сети: установите 'playwright'. "
            "Быстро: pip install -r requirements.txt && playwright install chromium"
        )
    log.info("Перехожу на загрузку через Playwright")
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(
            headless=not args.headful,
            args=["--no-sandbox", "--disable-setuid-sandbox"],
        )
        context = await browser.new_context(
            user_agent=DEFAULT_HEADERS["User-Agent"],
            locale="ru-RU",
            viewport={"width": 1280, "height": 800},
            extra_http_headers={
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            },
        )
        page = await context.new_page()
        try:
            log.info("Открываю %s", COIN_CATALOG_URL)
            await page.goto(
                COIN_CATALOG_URL,
                wait_until="domcontentloaded",
                timeout=args.playwright_timeout,
            )
            page_num = 1
            while True:
                if page_num > 1:
                    await asyncio.sleep(args.delay)
                log.info("Playwright: запрос списка page=%s", page_num)
                body = await fetch_list_page_playwright(
                    page,
                    page_num,
                    timeout_ms=args.playwright_timeout,
                    retries=args.retries,
                )
                if not body:
                    break
                rows, max_page = parse_list_response(body)
                max_page_seen = max(max_page_seen, max_page)
                for row in rows:
                    flat = coin_to_output(row)
                    if flat is None:
                        continue
                    key = dedupe_key(row, flat)
                    if not key or key in seen:
                        continue
                    seen.add(key)
                    merged.append(flat)
                log.info(
                    "Страница %s/%s: записей %s (всего уникальных %s)",
                    page_num,
                    max_page_seen,
                    len(rows),
                    len(merged),
                )
                if args.max_pages is not None and page_num >= args.max_pages:
                    break
                if page_num >= max_page_seen:
                    break
                page_num += 1
        finally:
            await browser.close()
    return max_page_seen, merged


async def scrape_vtb(args: argparse.Namespace) -> tuple[int, list[dict[str, Any]]]:
    """Скачивает все страницы, возвращает (max_page, список словарей для JSON)."""
    seen: set[str] = set()
    merged: list[dict[str, Any]] = []
    max_page_seen = 1
    return await scrape_vtb_via_playwright(args, seen, merged, max_page_seen)


# ============================================================================
# CLI
# ============================================================================


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет ВТБ (BFF API)")
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="итоговый JSON")
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="зарезервировано, с")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="повторов на страницу")
    p.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_PAGE_DELAY,
        help="пауза между запросами страниц, с",
    )
    p.add_argument(
        "--max-pages",
        type=int,
        default=None,
        help="максимум страниц (для отладки)",
    )
    p.add_argument(
        "--playwright-timeout",
        type=int,
        default=DEFAULT_TIMEOUT_MS,
        help="таймаут навигации Playwright, мс",
    )
    p.add_argument("--headful", action="store_true", help="окно браузера (ветка Playwright)")
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
    )


async def main_async(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет ВТБ (BFF)")
    log.info("=" * 60)
    started = datetime.now()

    max_page, coins = await scrape_vtb(args)

    result = {
        "scraped_at": started.isoformat(),
        "total_pages": max_page,
        "total_coins": len(coins),
        "coins": coins,
    }
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    log.info("=" * 60)
    log.info("  Страниц (maxPage): %s", max_page)
    log.info("  Монет в файле     : %s", len(coins))
    log.info("  Результат         : %s", args.output)
    log.info("=" * 60)
    return 0


def main(argv: list[str] | None = None) -> int:
    try:
        return asyncio.run(main_async(argv))
    except RuntimeError as e:
        log.error(str(e))
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
