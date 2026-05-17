#!/usr/bin/env python3
"""Скрейпер каталога монет goldenplata.ru (Золотая плата).

Источник: https://goldenplata.ru/catalog/
Сбор через Playwright (динамическая витрина).

Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
--investment-only: раздел российских инвестиционных монет (rossiyskiye).
--query: параметр q= в URL каталога.
"""
from __future__ import annotations

import argparse
import asyncio
import html
import json
import logging
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode

from playwright.async_api import Error as PlaywrightError, Route, async_playwright

BASE_URL = "https://goldenplata.ru"
CATALOG_URL = f"{BASE_URL}/catalog/"
INVESTMENT_CATALOG_URL = (
    f"{BASE_URL}/catalog/investitsionnye-monety/rossiyskiye/"
)
DEFAULT_DELAY = 0.4
DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3

BLOCKED_RESOURCE_TYPES = frozenset({"image", "media", "font"})
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

PAGEN_RE = re.compile(r"[?&]PAGEN_4=(\d+)", re.I)
ANALYTICS_RE = re.compile(
    r'<script type="application/json" class="js-analytics-payload">\s*(.*?)\s*</script>',
    re.S,
)
METAL_MAP = {
    "золото": "Золото",
    "серебро": "Серебро",
    "платина": "Платина",
    "палладий": "Палладий",
}

CAPTCHA_TITLE_HINTS = ("captcha",)
CAPTCHA_BODY_HINTS = (
    "не робот",
    "not a robot",
    "ползунк",
    "выровнять картинку",
)

log = logging.getLogger("goldenplata_scraper")


class CaptchaBlockedError(RuntimeError):
    """Сайт отдал CAPTCHA вместо каталога."""


@dataclass
class GoldenplataCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _item_id: str | None = None

    def to_dict(self) -> dict:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


def parse_price(value: object) -> float | None:
    if value is None:
        return None
    s = str(value).replace("\u00a0", " ").replace("\u202f", " ")
    m = re.search(r"\d[\d\s]*(?:[.,]\d+)?", s)
    if not m:
        return None
    raw = m.group(0).replace(" ", "").replace(",", ".")
    try:
        return float(raw)
    except ValueError:
        return None


def normalize_metal(value: str | None) -> str | None:
    if not value:
        return None
    s = value.strip().casefold()
    if s in METAL_MAP:
        return METAL_MAP[s]
    for key, label in METAL_MAP.items():
        if key in s:
            return label
    return None


def parse_weight_g(text: str) -> float | None:
    m = re.search(r"(\d+(?:[.,]\d+)?)\s*гр\.?", text.casefold())
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", "."))
    except ValueError:
        return None


def build_catalog_url(
    base_url: str,
    *,
    page: int,
    query: str,
) -> str:
    params: dict[str, str | int] = {}
    if query:
        params["q"] = query
    if page > 1:
        params["PAGEN_4"] = page
    if not params:
        return base_url
    sep = "&" if "?" in base_url else "?"
    return base_url + sep + urlencode(params)


def parse_total_pages(html_text: str) -> int:
    pages = [int(x) for x in PAGEN_RE.findall(html_text)]
    return max([1, *pages]) if pages else 1


def analytics_payload_to_coin(data: dict) -> GoldenplataCoin | None:
    name = html.unescape(str(data.get("item_name", "")).strip())
    if not name:
        return None

    item_id = str(data.get("item_id", "")).strip() or None
    metal = normalize_metal(str(data.get("item_variant", "")).strip()) or normalize_metal(name)
    weight_g = parse_weight_g(name)

    regular = parse_price(data.get("price"))
    card = parse_price(data.get("cardprice"))
    sell_price = regular if regular is not None else card
    if data.get("availability") is False:
        sell_price = None

    return GoldenplataCoin(
        name=name,
        catalog_number=item_id,
        metal=metal,
        weight_g=weight_g,
        buy_price=None,
        sell_price=sell_price,
        _item_id=item_id,
    )


def parse_coins_from_html(html_text: str) -> list[GoldenplataCoin]:
    coins: list[GoldenplataCoin] = []
    for raw_json in ANALYTICS_RE.findall(html_text):
        try:
            data = json.loads(raw_json)
        except json.JSONDecodeError:
            log.debug("Пропуск битого analytics JSON")
            continue
        if not isinstance(data, dict):
            continue
        coin = analytics_payload_to_coin(data)
        if coin is not None:
            coins.append(coin)
    return coins


def dedupe_key(coin: GoldenplataCoin) -> str:
    if coin._item_id:
        return f"id:{coin._item_id}"
    return f"fb:{coin.name}|{coin.weight_g}|{coin.metal}"


async def page_is_captcha(page) -> bool:
    title = (await page.title()).casefold()
    if any(h in title for h in CAPTCHA_TITLE_HINTS):
        return True
    try:
        body = (await page.inner_text("body", timeout=5_000))[:4_000].casefold()
    except PlaywrightError:
        return False
    return any(h in body for h in CAPTCHA_BODY_HINTS)


async def wait_captcha_cleared(page, timeout_sec: int, poll_sec: float = 1.0) -> bool:
    deadline = asyncio.get_event_loop().time() + timeout_sec
    while asyncio.get_event_loop().time() < deadline:
        if not await page_is_captcha(page):
            return True
        await asyncio.sleep(poll_sec)
    return not await page_is_captcha(page)


async def launch_chromium_browser(pw, *, headless: bool, browser_channel: str | None = None):
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


async def dismiss_cookie_banner(page) -> None:
    for selector in (
        'button:has-text("ОК")',
        'button:has-text("OK")',
        ".cookie-alert button",
    ):
        btn = page.locator(selector).first
        if await btn.count() > 0:
            try:
                await btn.click(timeout=2_000)
                await page.wait_for_timeout(500)
                return
            except PlaywrightError:
                pass


async def fetch_page_html(page, url: str, *, timeout: int, retries: int) -> str:
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            log.info("Открываю %s (попытка %s/%s)", url, attempt, retries)
            await page.goto(url, wait_until="domcontentloaded", timeout=timeout)
            await page.wait_for_timeout(2_000)
            await dismiss_cookie_banner(page)
            content = await page.content()
            if ANALYTICS_RE.search(content) or await page_is_captcha(page):
                return content
            await page.wait_for_timeout(1_500)
            return await page.content()
        except PlaywrightError as e:
            last_error = e
            log.warning("Навигация: попытка %s/%s — %s", attempt, retries, e)
            if attempt < retries:
                await asyncio.sleep(2 ** attempt)
    raise PlaywrightError(f"Не удалось загрузить {url}: {last_error}")


def resolve_catalog_base(args: argparse.Namespace) -> str:
    if args.investment_only:
        return INVESTMENT_CATALOG_URL
    return CATALOG_URL


async def scrape(args: argparse.Namespace) -> tuple[list[GoldenplataCoin], int]:
    coins: list[GoldenplataCoin] = []
    seen_keys: set[str] = set()
    catalog_base = resolve_catalog_base(args)
    query = (args.query or "").strip()
    total_pages = 0

    async with async_playwright() as pw:
        browser = await launch_chromium_browser(
            pw,
            headless=not args.headful,
            browser_channel=args.browser_channel or None,
        )
        context_kwargs: dict = {
            "user_agent": USER_AGENT,
            "locale": "ru-RU",
            "viewport": {"width": 1366, "height": 900},
            "extra_http_headers": {
                "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            },
        }
        if args.storage_state and args.storage_state.is_file():
            context_kwargs["storage_state"] = str(args.storage_state)
            log.info("Загружаю сессию из %s", args.storage_state)
        context = await browser.new_context(**context_kwargs)
        await context.route("**/*", block_assets)
        page = await context.new_page()

        try:
            first_url = build_catalog_url(catalog_base, page=1, query=query)
            first_html = await fetch_page_html(
                page, first_url, timeout=args.timeout, retries=args.retries
            )

            if await page_is_captcha(page):
                if args.wait_captcha_secs > 0 and args.headful:
                    log.warning(
                        "CAPTCHA — пройдите проверку в окне браузера (до %s с)...",
                        args.wait_captcha_secs,
                    )
                    if not await wait_captcha_cleared(page, args.wait_captcha_secs):
                        raise CaptchaBlockedError(
                            "CAPTCHA не пройдена за отведённое время"
                        )
                if await page_is_captcha(page):
                    raise CaptchaBlockedError(
                        "goldenplata.ru показал CAPTCHA вместо каталога. "
                        "Попробуйте --headful --wait-captcha-secs 120."
                    )

            pages_to_visit = parse_total_pages(first_html)
            if args.max_pages > 0:
                pages_to_visit = min(pages_to_visit, args.max_pages)
            log.info("Страниц к обходу: %s", pages_to_visit)

            for page_num in range(1, pages_to_visit + 1):
                url = build_catalog_url(catalog_base, page=page_num, query=query)
                html_text = (
                    first_html
                    if page_num == 1
                    else await fetch_page_html(
                        page, url, timeout=args.timeout, retries=args.retries
                    )
                )
                page_coins = parse_coins_from_html(html_text)
                added = 0
                for coin in page_coins:
                    key = dedupe_key(coin)
                    if key in seen_keys:
                        log.warning(
                            "Дубликат (ключ %s): «%s», id=%s",
                            key,
                            coin.name,
                            coin._item_id,
                        )
                        continue
                    seen_keys.add(key)
                    if coin.sell_price is None:
                        log.warning("Нет цены продажи: «%s»", coin.name)
                    coins.append(coin)
                    added += 1
                    if args.max_items is not None and len(coins) >= args.max_items:
                        break
                total_pages += 1
                log.info(
                    "Страница %s: найдено %s, добавлено %s",
                    page_num,
                    len(page_coins),
                    added,
                )
                if args.max_items is not None and len(coins) >= args.max_items:
                    break
                if page_num < pages_to_visit and args.delay > 0:
                    await asyncio.sleep(args.delay)

            if args.save_storage_state:
                await context.storage_state(path=str(args.save_storage_state))
                log.info("Сессия сохранена: %s", args.save_storage_state)
        finally:
            await browser.close()

    return coins, total_pages


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет goldenplata.ru")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="таймаут, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="попыток навигации")
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="пауза между страницами, с")
    p.add_argument("--max-pages", type=int, default=0, help="лимит страниц (0 = все)")
    p.add_argument("--max-items", type=int, default=None, help="лимит монет")
    p.add_argument(
        "--query",
        default="",
        help="поиск на витрине (URL-параметр q=)",
    )
    p.add_argument(
        "--investment-only",
        action="store_true",
        help=f"только российские инвестиционные монеты ({INVESTMENT_CATALOG_URL})",
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
    log.info("  Скрейпер каталога монет goldenplata.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[GoldenplataCoin] = []

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
    if args.investment_only:
        result["investment_only"] = True
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
