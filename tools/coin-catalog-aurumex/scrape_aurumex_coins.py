#!/usr/bin/env python3
"""Скрейпер каталога монет aurumex.ru.

Источник: https://aurumex.ru/catalog?availability=true
Сбор: Playwright (сессия) + GET Nuxt payload каталога (без /products/).

Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
--query: пост-фильтр по name / catalog_number (на сайте поиска нет).
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
from pathlib import Path
from typing import Any

from playwright.async_api import APIRequestContext, Error as PlaywrightError, async_playwright

PUBLIC_URL = "https://aurumex.ru"
CATALOG_PATH = "/catalog"
CATALOG_URL = f"{PUBLIC_URL}{CATALOG_PATH}?availability=true"
PAYLOAD_SUFFIX = "?availability=true"
PAYLOAD_PAGE_URL_TPL = f"{PUBLIC_URL}{CATALOG_PATH}/page/{{page}}/_payload.json{PAYLOAD_SUFFIX}"
PAYLOAD_URL_PAGE1 = f"{PUBLIC_URL}{CATALOG_PATH}/_payload.json{PAYLOAD_SUFFIX}"

DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.5
DEFAULT_MAX_PAGES = 10

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

PACK_COUNT_RE = re.compile(r"(\d+)\s*шт", re.IGNORECASE)

CAPTCHA_TITLE_HINTS = ("captcha",)
CAPTCHA_BODY_HINTS = (
    "не робот",
    "not a robot",
    "ползунк",
    "выровнять картинку",
)

METAL_MAP = {
    "gold": "Золото",
    "silver": "Серебро",
}

log = logging.getLogger("aurumex_scraper")


class CaptchaBlockedError(RuntimeError):
    """Сайт отдал CAPTCHA вместо каталога."""


@dataclass
class AurumexCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None

    def to_dict(self) -> dict:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


def deref_cell(store: list, ptr: Any) -> Any:
    if not isinstance(ptr, int):
        return ptr
    if ptr < 0 or ptr >= len(store):
        return ptr
    return store[ptr]


def to_float(value: Any) -> float | None:
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


def parse_weight_g(value: Any) -> float | None:
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


def category_to_metal(slug: Any) -> str | None:
    if not isinstance(slug, str):
        return None
    return METAL_MAP.get(slug.strip().casefold())


def parse_pack_count(title: str) -> int | None:
    m = PACK_COUNT_RE.search(title)
    if not m:
        return None
    n = int(m.group(1))
    return n if n > 1 else None


def parse_price_tiers(raw: dict, store: list) -> list[dict[str, Any]]:
    prices_ptr = raw.get("prices")
    prices = deref_cell(store, prices_ptr)
    if not isinstance(prices, dict):
        return []

    tiers: list[dict[str, Any]] = []
    for tier_ref in prices.values():
        tier = deref_cell(store, tier_ref)
        if not isinstance(tier, dict):
            continue
        from_val = deref_cell(store, tier.get("from"))
        to_val = deref_cell(store, tier.get("to"))
        value = to_float(deref_cell(store, tier.get("value")))
        if from_val is None and value is None:
            continue
        tiers.append(
            {
                "from": int(from_val) if isinstance(from_val, (int, float, str)) and str(from_val).isdigit() else from_val,
                "to": to_val,
                "value": value,
            }
        )
    return tiers


def _is_pack_total(value: float, pack_n: int, price_per_ounce: float | None) -> bool:
    if not price_per_ounce or price_per_ounce <= 0:
        return False
    ratio = value / price_per_ounce
    return abs(round(ratio) - pack_n) <= 1


def resolve_sell_price(
    raw: dict,
    store: list,
    *,
    title: str,
    list_price: float | None,
    price_per_ounce: float | None,
    tiers: list[dict[str, Any]],
) -> float | None:
    pack_n = parse_pack_count(title)
    multi_tier = len(tiers) >= 2

    tier_one = next((t for t in tiers if t.get("from") == 1 and t.get("value") is not None), None)
    if tier_one is not None:
        val = tier_one["value"]
        if not (pack_n and _is_pack_total(val, pack_n, price_per_ounce)):
            return val

    if pack_n and price_per_ounce and list_price is not None:
        if abs(round(list_price / price_per_ounce) - pack_n) <= 1:
            return price_per_ounce
        return list_price / pack_n

    if list_price is not None:
        return list_price

    if price_per_ounce is not None and not multi_tier:
        return price_per_ounce

    return None


def resolve_buy_price(
    raw: dict,
    store: list,
    *,
    title: str,
    list_price: float | None,
    price_per_ounce: float | None,
) -> float | None:
    purchase = to_float(deref_cell(store, raw.get("pricePurchase")))
    if purchase is None:
        return None

    pack_n = parse_pack_count(title)
    if pack_n and price_per_ounce and list_price is not None:
        if abs(round(list_price / price_per_ounce) - pack_n) <= 1:
            if abs(round(purchase / price_per_ounce) - pack_n) <= 1:
                return purchase / pack_n
    return purchase


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


def extract_coins_from_store(store: list) -> list[AurumexCoin]:
    block = find_catalog_block(store)
    if not block:
        raise RuntimeError("Не найден блок каталога с ключом coins в payload")

    coins_ptr = block["coins"]
    coins_indices = deref_cell(store, coins_ptr)
    if not isinstance(coins_indices, list):
        raise RuntimeError("coins не является списком")

    coins: list[AurumexCoin] = []
    for ref in coins_indices:
        if not isinstance(ref, int):
            continue
        raw = deref_cell(store, ref)
        if not isinstance(raw, dict):
            continue

        try:
            is_available = deref_cell(store, raw["isAvailable"])
            if is_available is not True:
                continue

            article = str(deref_cell(store, raw["id"]))
            title = deref_cell(store, raw["title"])
            cat = deref_cell(store, raw["category"])
            weight = deref_cell(store, raw["weight"])
            list_price = to_float(deref_cell(store, raw.get("price")))
            price_per_ounce = to_float(deref_cell(store, raw.get("pricePerOunce")))
        except KeyError:
            continue

        if not isinstance(title, str) or not title.strip():
            log.warning("Пропуск монеты без названия (id=%s)", article)
            continue

        name = title.strip()
        tiers = parse_price_tiers(raw, store)
        sell_price = resolve_sell_price(
            raw,
            store,
            title=name,
            list_price=list_price,
            price_per_ounce=price_per_ounce,
            tiers=tiers,
        )
        buy_price = resolve_buy_price(
            raw,
            store,
            title=name,
            list_price=list_price,
            price_per_ounce=price_per_ounce,
        )

        coins.append(
            AurumexCoin(
                name=name,
                catalog_number=article,
                metal=category_to_metal(cat),
                weight_g=parse_weight_g(weight),
                buy_price=buy_price,
                sell_price=sell_price,
            )
        )

    return coins


def payload_url_for_page(page: int) -> str:
    if page <= 1:
        return PAYLOAD_URL_PAGE1
    return PAYLOAD_PAGE_URL_TPL.format(page=page)


async def page_is_captcha(page) -> bool:
    title = (await page.title()).casefold()
    if any(h in title for h in CAPTCHA_TITLE_HINTS):
        return True
    try:
        body = (await page.inner_text("body", timeout=5_000))[:4_000].casefold()
    except PlaywrightError:
        return False
    return any(h in body for h in CAPTCHA_BODY_HINTS)


async def launch_browser(pw, *, headless: bool, browser_channel: str | None = None):
    launch_kwargs: dict = {"headless": headless, "args": LAUNCH_ARGS}
    errors: list[str] = []

    if browser_channel:
        try:
            browser = await pw.chromium.launch(channel=browser_channel, **launch_kwargs)
            log.info("Браузер: %s", browser_channel)
            return browser
        except PlaywrightError as e:
            raise PlaywrightError(
                f"Не удалось запустить браузер channel={browser_channel}: {e}"
            ) from e

    for channel in BROWSER_CHANNELS:
        try:
            browser = await pw.chromium.launch(channel=channel, **launch_kwargs)
            log.info("Браузер: %s", channel)
            return browser
        except PlaywrightError as e:
            errors.append(f"{channel}: {e}")

    browser = await pw.chromium.launch(**launch_kwargs)
    log.info("Браузер: playwright bundled chromium")
    return browser


async def fetch_payload_json(
    request: APIRequestContext,
    url: str,
    *,
    retries: int,
    delay: float,
    timeout_ms: int,
) -> list:
    last_error: Exception | None = None
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Referer": CATALOG_URL,
    }
    for attempt in range(1, retries + 1):
        try:
            log.info("Загрузка payload: %s (попытка %s/%s)", url, attempt, retries)
            resp = await request.get(url, headers=headers, timeout=timeout_ms)
            if not resp.ok:
                raise RuntimeError(f"HTTP {resp.status} для {url}")
            return await resp.json()
        except Exception as exc:
            last_error = exc
            log.warning("Payload %s: попытка %s/%s — %s", url, attempt, retries, exc)
            if attempt < retries:
                await asyncio.sleep(delay * (2 ** (attempt - 1)))
    raise RuntimeError(f"Не удалось загрузить {url}: {last_error}")


def apply_query_filter(coins: list[AurumexCoin], query: str) -> list[AurumexCoin]:
    q = query.casefold()
    return [
        c
        for c in coins
        if q in c.name.casefold()
        or (c.catalog_number and q in c.catalog_number.casefold())
    ]


async def scrape(args: argparse.Namespace) -> tuple[list[AurumexCoin], int]:
    retries = max(1, args.retries)
    delay = max(0.0, args.delay)
    timeout_ms = max(1000, args.timeout)
    max_pages = max(1, args.max_pages)

    by_id: dict[str, AurumexCoin] = {}
    pages_scraped = 0

    async with async_playwright() as pw:
        browser = await launch_browser(
            pw,
            headless=not args.headful,
            browser_channel=args.browser_channel or None,
        )
        try:
            context_kwargs: dict = {
                "user_agent": USER_AGENT,
                "ignore_https_errors": not args.secure_ssl,
            }
            if args.storage_state and args.storage_state.is_file():
                context_kwargs["storage_state"] = str(args.storage_state)
            context = await browser.new_context(**context_kwargs)
            page = await context.new_page()

            log.info("Открываю каталог: %s", CATALOG_URL)
            await page.goto(CATALOG_URL, wait_until="domcontentloaded", timeout=timeout_ms)
            if await page_is_captcha(page):
                if args.headful and args.wait_captcha_secs > 0:
                    log.info("Ожидание прохождения CAPTCHA (%s с)...", args.wait_captcha_secs)
                    deadline = asyncio.get_event_loop().time() + args.wait_captcha_secs
                    while asyncio.get_event_loop().time() < deadline:
                        if not await page_is_captcha(page):
                            break
                        await asyncio.sleep(1.0)
                if await page_is_captcha(page):
                    raise CaptchaBlockedError("CAPTCHA на странице каталога Aurumex")

            request = context.request

            for page_num in range(1, max_pages + 1):
                url = payload_url_for_page(page_num)
                store = await fetch_payload_json(
                    request,
                    url,
                    retries=retries,
                    delay=delay,
                    timeout_ms=timeout_ms,
                )
                if not isinstance(store, list):
                    raise RuntimeError(f"Неверный формат payload: {url}")

                page_coins = extract_coins_from_store(store)
                pages_scraped += 1
                before = len(by_id)
                for coin in page_coins:
                    key = coin.catalog_number or coin.name
                    if key in by_id:
                        log.warning("Пропуск дубликата: %s", key)
                        continue
                    by_id[key] = coin

                added = len(by_id) - before
                log.info(
                    "Страница %s: в payload=%s, добавлено=%s, всего=%s",
                    page_num,
                    len(page_coins),
                    added,
                    len(by_id),
                )

                if added == 0 and len(by_id) > 0:
                    log.info("Остановка: новые монеты на странице %s не добавились", page_num)
                    break

            if args.save_storage_state:
                await context.storage_state(path=str(args.save_storage_state))
                log.info("Сессия сохранена: %s", args.save_storage_state)
        finally:
            await browser.close()

    coins = list(by_id.values())
    query = (args.query or "").strip()
    if query:
        coins = apply_query_filter(coins, query)
        log.info("После фильтра --query=%r: %s монет", query, len(coins))

    return coins, pages_scraped


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет aurumex.ru")
    p.add_argument(
        "--query",
        default="",
        help="пост-фильтр по name / catalog_number (пусто — весь каталог в наличии)",
    )
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="таймаут, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="попыток запроса")
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="пауза между retry, с")
    p.add_argument(
        "--max-pages",
        type=int,
        default=DEFAULT_MAX_PAGES,
        help="лимит страниц payload (по умолчанию 10)",
    )
    p.add_argument("--headful", action="store_true", help="показать окно браузера")
    p.add_argument(
        "--browser-channel",
        default="",
        choices=["", "chrome", "msedge", "chromium"],
        help="системный браузер (пусто — авто)",
    )
    p.add_argument("--storage-state", type=Path, default=None, help="JSON сессии")
    p.add_argument("--save-storage-state", type=Path, default=None, help="сохранить сессию")
    p.add_argument(
        "--wait-captcha-secs",
        type=int,
        default=0,
        help="ждать ручного прохождения CAPTCHA (только с --headful)",
    )
    p.add_argument(
        "--secure-ssl",
        action="store_true",
        help="включить проверку TLS (по умолчанию отключена)",
    )
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
        stream=sys.stderr,
    )


async def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет aurumex.ru")
    log.info("=" * 60)
    log.info("URL каталога: %s", CATALOG_URL)

    started_at = datetime.now()
    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[AurumexCoin] = []

    try:
        coins, total_pages = await scrape(args)
    except CaptchaBlockedError as e:
        scrape_status = "captcha_blocked"
        error_message = str(e)
        log.error("%s", e)
    except PlaywrightError as e:
        scrape_status = "error"
        error_message = str(e)
        log.error("Ошибка Playwright: %s", e)
    except RuntimeError as e:
        scrape_status = "error"
        error_message = str(e)
        log.error("%s", e)

    result: dict = {
        "scraped_at": started_at.isoformat(),
        "scrape_status": scrape_status,
        "total_pages": total_pages,
        "total_coins": len(coins),
        "coins": [c.to_dict() for c in coins],
    }
    query = (args.query or "").strip()
    if query:
        result["query"] = query
    if error_message:
        result["error"] = error_message

    sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

    log.info("=" * 60)
    log.info("  Найдено монет : %s", len(coins))
    log.info("  Страниц       : %s", total_pages)
    log.info("  Статус        : %s", scrape_status)
    log.info("=" * 60)
    return 0 if scrape_status == "ok" else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
