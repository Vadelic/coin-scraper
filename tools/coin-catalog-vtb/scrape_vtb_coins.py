#!/usr/bin/env python3
"""Скрейпер каталога монет ВТБ (www.vtb.ru).

Сбор через Playwright: витрина + BFF POST /api/bff/api/v1/coin/list.
Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
Опционально: --query — фильтрация по name / catalog_number / metal (локально);
--investment-only — фильтр BFF coinKind=«Инвестиционные».
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
from typing import Any

from playwright.async_api import Error as PlaywrightError, async_playwright

BASE_SITE = "https://www.vtb.ru"
LIST_PATH = "/api/bff/api/v1/coin/list"
COIN_CATALOG_URL = (
    f"{BASE_SITE}/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/"
)

COIN_KIND_FILTER_ID = "coinKind"
INVESTMENT_KIND_VALUE = "Инвестиционные"

DEFAULT_DELAY = 0.35
DEFAULT_TIMEOUT_MS = 45_000
DEFAULT_RETRIES = 3

BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

CAPTCHA_TITLE_HINTS = ("captcha", "robot", "робот")
CAPTCHA_BODY_HINTS = (
    "не робот",
    "not a robot",
    "проверка безопасности",
    "access denied",
)

METAL_LINE_RE = re.compile(
    r"^(золото|серебро|платина|палладий)\b",
    re.IGNORECASE,
)

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


class CaptchaBlockedError(RuntimeError):
    pass


@dataclass
class VtbCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _id: str | None = None

    def to_dict(self) -> dict:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


def build_list_url(page: int) -> str:
    return f"{BASE_SITE}{LIST_PATH}?page={int(page)}"


def build_investment_filters() -> list[dict[str, Any]]:
    return [{"id": COIN_KIND_FILTER_ID, "values": [INVESTMENT_KIND_VALUE]}]


def build_payload(filters: list[Any] | None = None) -> dict:
    return {"filters": list(filters) if filters else []}


def resolve_api_filters(args: argparse.Namespace) -> list[Any] | None:
    if args.investment_only:
        return build_investment_filters()
    return None


def parse_list_response(body: dict) -> tuple[list[dict], int]:
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


def normalize_metal(text: str | None) -> str | None:
    if not text:
        return None
    m = METAL_LINE_RE.match(text.strip())
    if m:
        name = m.group(1)
        return name[0].upper() + name[1:].lower()
    return text.strip() or None


def _float_or_none(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def dedupe_key(row: dict, coin: VtbCoin) -> str:
    rid = row.get("id")
    if rid is not None and str(rid).strip():
        return str(rid).strip()
    if coin.catalog_number:
        return coin.catalog_number
    return coin.name


def coin_matches_query(coin: VtbCoin, query: str) -> bool:
    q = query.casefold().strip()
    if not q:
        return True
    hay = " ".join(
        x
        for x in (
            coin.name,
            coin.catalog_number or "",
            coin.metal or "",
        )
        if x
    ).casefold()
    return q in hay


def row_to_coin(item: dict) -> VtbCoin | None:
    if not isinstance(item, dict):
        return None
    name = (item.get("name") or "").strip()
    raw_article = item.get("article")
    article = str(raw_article).strip() if raw_article is not None else ""
    if not name and not article:
        return None

    cid = item.get("id")
    coin_id = str(cid).strip() if cid is not None else None

    return VtbCoin(
        name=name or article,
        catalog_number=article or None,
        metal=normalize_metal((item.get("metal") or "").strip() or None),
        weight_g=_float_or_none(item.get("mass")),
        buy_price=None,
        sell_price=_float_or_none(item.get("price1")),
        _id=coin_id,
    )


async def page_is_captcha(page) -> bool:
    title = (await page.title()).casefold()
    if any(h in title for h in CAPTCHA_TITLE_HINTS):
        return True
    try:
        body = (await page.locator("body").inner_text(timeout=5000)).casefold()
    except PlaywrightError:
        return False
    return any(h in body for h in CAPTCHA_BODY_HINTS)


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


async def fetch_list_page(
    page,
    page_num: int,
    *,
    filters: list[Any] | None,
    retries: int,
) -> dict | None:
    url = build_list_url(page_num)
    payload = build_payload(filters)
    for attempt in range(1, retries + 1):
        try:
            data = await page.evaluate(
                _FETCH_JS,
                {"url": url, "body": payload},
            )
            return data if isinstance(data, dict) else None
        except (PlaywrightError, Exception) as e:
            log.warning(
                "Страница %s, попытка %s/%s — %s",
                page_num,
                attempt,
                retries,
                e,
            )
            if attempt < retries:
                await asyncio.sleep(2**attempt)
    return None


async def scrape_all_pages(args: argparse.Namespace) -> tuple[int, list[VtbCoin]]:
    seen: set[str] = set()
    coins: list[VtbCoin] = []
    pages_processed = 0
    url_query = (args.query or "").strip()
    api_filters = resolve_api_filters(args)

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
        page = await context.new_page()

        log.info("Открываю %s", COIN_CATALOG_URL)
        await page.goto(
            COIN_CATALOG_URL,
            wait_until="domcontentloaded",
            timeout=args.timeout,
        )

        if await page_is_captcha(page):
            raise CaptchaBlockedError(
                "ВТБ показал страницу проверки вместо каталога. "
                "Попробуйте --headful и пройдите проверку вручную."
            )

        if args.investment_only:
            log.info(
                "Фильтр BFF: %s=%s",
                COIN_KIND_FILTER_ID,
                INVESTMENT_KIND_VALUE,
            )
        if url_query:
            log.info("Фильтр query=«%s» (локально по name / catalog_number / metal)", url_query)

        page_num = 1
        max_page_seen = 1

        while True:
            if page_num > 1:
                await asyncio.sleep(args.delay)

            log.info("Запрос списка page=%s", page_num)
            body = await fetch_list_page(
                page,
                page_num,
                filters=api_filters,
                retries=args.retries,
            )
            if not body:
                log.error("Страница %s: пустой ответ BFF — останов", page_num)
                break

            rows, max_page = parse_list_response(body)
            max_page_seen = max(max_page_seen, max_page)
            pages_processed += 1

            for row in rows:
                coin = row_to_coin(row)
                if coin is None:
                    log.warning("Пропуск записи: нет name и article")
                    continue
                if not coin.name:
                    log.warning(
                        "Пропуск записи: пустое name, article=%s",
                        coin.catalog_number,
                    )
                    continue
                if url_query and not coin_matches_query(coin, url_query):
                    continue
                key = dedupe_key(row, coin)
                if not key:
                    continue
                if key in seen:
                    log.warning(
                        "Дубликат (id %s): «%s», артикул=%s",
                        key,
                        coin.name,
                        coin.catalog_number,
                    )
                    continue
                seen.add(key)
                coins.append(coin)
                if coin.sell_price is None:
                    log.warning(
                        "Нет sell_price для «%s» (артикул %s)",
                        coin.name,
                        coin.catalog_number,
                    )

            log.info(
                "Страница %s/%s: записей %s (подходящих всего: %s)",
                page_num,
                max_page_seen,
                len(rows),
                len(coins),
            )

            if args.max_pages is not None and page_num >= args.max_pages:
                break
            if page_num >= max_page_seen:
                break
            page_num += 1

        await browser.close()

    return pages_processed, coins


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет ВТБ (Playwright + BFF)")
    p.add_argument(
        "--query",
        default="",
        help="фильтр по подстроке в name, catalog_number, metal (локально после загрузки)",
    )
    p.add_argument(
        "--investment-only",
        action="store_true",
        help="только инвестиционные монеты (BFF-фильтр coinKind=«Инвестиционные»)",
    )
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="пауза между страницами, с")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="таймаут навигации, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="повторов на страницу")
    p.add_argument("--max-pages", type=int, default=None, help="максимум страниц (отладка)")
    p.add_argument("--headful", action="store_true", help="окно браузера")
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
    log.info("  Скрейпер каталога монет ВТБ")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[VtbCoin] = []

    try:
        total_pages, coins = await scrape_all_pages(args)
        if total_pages == 0 and not coins:
            scrape_status = "error"
            error_message = "Не удалось загрузить каталог (0 страниц BFF)"
    except CaptchaBlockedError as e:
        scrape_status = "captcha_blocked"
        error_message = str(e)
        log.error("%s", e)
    except PlaywrightError as e:
        scrape_status = "error"
        error_message = str(e)
        log.error("Ошибка Playwright: %s", e)

    result: dict[str, Any] = {
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
