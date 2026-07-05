#!/usr/bin/env python3
"""Скрейпер каталога монет ATB (atb.su).

Источник: https://www.atb.su/vklady-i-scheta/monety/
Сбор через Playwright (сессия) + AJAX POST / detail GET через APIRequestContext.

Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number, name, metal, weight_g, buy_price, sell_price.
Опционально: --query (поле name в AJAX), --investment-only (category=479).
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
from urllib.parse import quote, urljoin

from playwright.async_api import APIRequestContext, Error as PlaywrightError, async_playwright

CATALOG_URL = "https://www.atb.su/vklady-i-scheta/monety/"
BASE_URL = "https://www.atb.su"
INVESTMENT_CATEGORY = "479"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.5

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

AJAX_HEADERS = {
    "Accept": "text/html, */*;q=0.1",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "Origin": BASE_URL,
    "Referer": CATALOG_URL,
    "X-Requested-With": "XMLHttpRequest",
}

DETAIL_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Referer": CATALOG_URL,
}

METAL_LINE_RE = re.compile(
    r"^(золото|серебро|платина|палладий|медно-никелевый сплав)\b",
    re.IGNORECASE,
)

CAPTCHA_BODY_HINTS = (
    "не робот",
    "not a robot",
    "captcha",
    "ползунк",
)

BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

log = logging.getLogger("atb_scraper")


class CaptchaBlockedError(RuntimeError):
    """Сайт отдал CAPTCHA вместо каталога."""


@dataclass
class AtbCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _url: str | None = None

    def to_dict(self) -> dict:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


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


def parse_weight_g(text: str) -> float | None:
    if not text:
        return None
    m = re.search(r"\d+(?:[.,]\d+)?", text)
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", "."))
    except ValueError:
        return None


def normalize_metal(text: str | None) -> str | None:
    if not text:
        return None
    m = METAL_LINE_RE.match(text.strip())
    if m:
        name = m.group(1)
        return name[0].upper() + name[1:].lower()
    return None


def strip_tags(html: str) -> str:
    text = re.sub(r"<br\s*/?>", "\n", html, flags=re.I)
    text = re.sub(r"</p\s*>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"&nbsp;", " ", text)
    text = re.sub(r"&amp;", "&", text)
    text = re.sub(r"&quot;", '"', text)
    text = re.sub(r"&#34;", '"', text)
    text = re.sub(r"&#39;", "'", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def parse_detail_fields(detail_html: str) -> tuple[str | None, str | None, float | None]:
    rows = re.findall(
        r"<tr>\s*<td>(.*?)</td>\s*<td>(.*?)</td>\s*</tr>",
        detail_html,
        flags=re.I | re.S,
    )
    kv: dict[str, str] = {}
    for key_html, value_html in rows:
        key = strip_tags(key_html)
        value = strip_tags(value_html)
        if key and value:
            kv[key.casefold()] = value

    catalog_number = kv.get("каталожный номер")
    metal = normalize_metal(kv.get("металл, проба") or kv.get("металл"))
    weight_raw = kv.get("масса общая, г") or kv.get("масса, г") or kv.get("масса")
    weight_g = parse_weight_g(weight_raw or "")
    return catalog_number, metal, weight_g


def build_request_body(*, category: str, query: str) -> str:
    name_param = quote(query.strip(), safe="") if query.strip() else ""
    return (
        "ajax=true&"
        f"category={category}&"
        "type=js-coins&"
        "country=&"
        "metall=&"
        "sample=&"
        "denomination=&"
        "year=&"
        "count=99999&"
        f"name={name_param}&"
        "page=1"
    )


def detect_captcha(html: str) -> bool:
    lower = html.casefold()
    return any(hint in lower for hint in CAPTCHA_BODY_HINTS)


async def request_with_retry(
    request: APIRequestContext,
    method: str,
    url: str,
    *,
    retries: int,
    delay: float,
    timeout_ms: int,
    headers: dict[str, str] | None = None,
    data: str | None = None,
) -> str:
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            if method == "POST":
                resp = await request.post(
                    url,
                    data=data,
                    headers=headers,
                    timeout=timeout_ms,
                )
            else:
                resp = await request.get(
                    url,
                    headers=headers,
                    timeout=timeout_ms,
                )
            if not resp.ok:
                raise RuntimeError(f"HTTP {resp.status} для {url}")
            body = await resp.text()
            if not body.strip():
                raise RuntimeError(f"Пустой ответ: {url}")
            return body
        except (PlaywrightError, RuntimeError) as exc:
            last_error = exc
            log.warning("Попытка %s/%s: %s", attempt, retries, exc)
            if attempt < retries:
                await asyncio.sleep(delay * (2 ** (attempt - 1)))
    raise RuntimeError(f"Не удалось получить {url}: {last_error}")


async def fetch_ajax_fragment(
    request: APIRequestContext,
    *,
    category: str,
    query: str,
    retries: int,
    delay: float,
    timeout_ms: int,
) -> str:
    body = build_request_body(category=category, query=query)
    return await request_with_retry(
        request,
        "POST",
        CATALOG_URL,
        retries=retries,
        delay=delay,
        timeout_ms=timeout_ms,
        headers=AJAX_HEADERS,
        data=body,
    )


async def fetch_detail_html(
    request: APIRequestContext,
    url: str,
    *,
    retries: int,
    delay: float,
    timeout_ms: int,
) -> str:
    return await request_with_retry(
        request,
        "GET",
        url,
        retries=retries,
        delay=delay,
        timeout_ms=timeout_ms,
        headers=DETAIL_HEADERS,
    )


async def parse_card(
    request: APIRequestContext,
    card_html: str,
    href: str,
    *,
    retries: int,
    delay: float,
    timeout_ms: int,
) -> AtbCoin | None:
    name_match = re.search(r'class="coins-item__name"\s*>(.*?)</div>', card_html, flags=re.S)
    if not name_match:
        log.warning("Пропуск карточки: пустое имя")
        return None
    name = strip_tags(name_match.group(1))
    if not name:
        log.warning("Пропуск карточки: пустое имя")
        return None

    price_match = re.search(
        r'class="coins-item__price"\s*>(.*?)</(?:motion\.)?div>',
        card_html,
        flags=re.S,
    )
    sell_price = parse_price(strip_tags(price_match.group(1))) if price_match else None
    if sell_price is None and price_match:
        log.warning("Нет цены на карточке: %s", name)

    buy_match = re.search(
        r'class="coins-item__cost"\s*>(.*?)</span>',
        card_html,
        flags=re.S,
    )
    buy_price = parse_price(strip_tags(buy_match.group(1))) if buy_match else None

    coin_url = urljoin(BASE_URL, href)
    detail_html = await fetch_detail_html(
        request,
        coin_url,
        retries=retries,
        delay=delay,
        timeout_ms=timeout_ms,
    )
    catalog_number, metal, weight_g = parse_detail_fields(detail_html)

    return AtbCoin(
        name=name,
        catalog_number=catalog_number,
        metal=metal,
        weight_g=weight_g,
        buy_price=buy_price,
        sell_price=sell_price,
        _url=coin_url,
    )


async def parse_coins_from_fragment(
    request: APIRequestContext,
    fragment_html: str,
    *,
    retries: int,
    delay: float,
    timeout_ms: int,
) -> list[AtbCoin]:
    cards = re.findall(
        r'<a\s+class="coins-item[^"]*"\s+href="(/vklady-i-scheta/monety/[^"]+/?)"[^>]*>(.*?)</a>',
        fragment_html,
        flags=re.S | re.I,
    )
    log.info("Карточек в ответе: %s", len(cards))

    coins: list[AtbCoin] = []
    seen_urls: set[str] = set()
    for index, (href, card_html) in enumerate(cards, start=1):
        coin = await parse_card(
            request,
            card_html,
            href,
            retries=retries,
            delay=delay,
            timeout_ms=timeout_ms,
        )
        if not coin:
            continue
        if coin._url in seen_urls:
            log.warning("Пропуск дубликата: %s", coin._url)
            continue
        seen_urls.add(coin._url)
        coins.append(coin)
        if index % 20 == 0:
            log.info("Обработано карточек: %s/%s", index, len(cards))
    return coins


async def launch_browser(pw, *, headless: bool):
    launch_kwargs = {"headless": headless, "args": LAUNCH_ARGS}
    for channel in BROWSER_CHANNELS:
        try:
            browser = await pw.chromium.launch(channel=channel, **launch_kwargs)
            log.info("Браузер: %s", channel)
            return browser
        except PlaywrightError:
            continue
    browser = await pw.chromium.launch(**launch_kwargs)
    log.info("Браузер: playwright bundled chromium")
    return browser


async def scrape(args: argparse.Namespace) -> list[AtbCoin]:
    category = INVESTMENT_CATEGORY if args.investment_only else ""
    query = (args.query or "").strip()
    retries = max(1, args.retries)
    delay = max(0.0, args.delay)
    timeout_ms = max(1000, args.timeout)

    async with async_playwright() as pw:
        browser = await launch_browser(pw, headless=not args.headful)
        try:
            context = await browser.new_context(
                user_agent=USER_AGENT,
                ignore_https_errors=not args.secure_ssl,
            )
            page = await context.new_page()
            log.info("Открываю каталог: %s", CATALOG_URL)
            await page.goto(CATALOG_URL, wait_until="domcontentloaded", timeout=timeout_ms)
            main_html = await page.content()
            if detect_captcha(main_html):
                raise CaptchaBlockedError("CAPTCHA на странице каталога ATB")

            request = context.request
            fragment = await fetch_ajax_fragment(
                request,
                category=category,
                query=query,
                retries=retries,
                delay=delay,
                timeout_ms=timeout_ms,
            )
            coins = await parse_coins_from_fragment(
                request,
                fragment,
                retries=retries,
                delay=delay,
                timeout_ms=timeout_ms,
            )
            if not coins:
                if "coins-select__no-result" in fragment or "ничего не найдено" in fragment.casefold():
                    log.info("По запросу монет не найдено")
                else:
                    raise RuntimeError("Не удалось распарсить монеты из ответа")
            return coins
        finally:
            await browser.close()


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер монет ATB (atb.su)")
    p.add_argument(
        "--query",
        default="",
        help="строка поиска (поле name в AJAX POST; пусто — без фильтра)",
    )
    p.add_argument(
        "--investment-only",
        action="store_true",
        help="только инвестиционные монеты (category=479)",
    )
    p.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT_MS,
        help=f"таймаут запросов в мс (по умолчанию: {DEFAULT_TIMEOUT_MS})",
    )
    p.add_argument(
        "--retries",
        type=int,
        default=DEFAULT_RETRIES,
        help=f"число попыток (по умолчанию: {DEFAULT_RETRIES})",
    )
    p.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_DELAY,
        help=f"базовая задержка между retry, с (по умолчанию: {DEFAULT_DELAY})",
    )
    p.add_argument("--headful", action="store_true", help="показать окно браузера")
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
    log.info("  Скрейпер каталога монет ATB")
    log.info("=" * 60)
    log.info("URL каталога: %s", CATALOG_URL)
    if args.investment_only:
        log.info("Фильтр: только инвестиционные (category=%s)", INVESTMENT_CATEGORY)
    if args.query and args.query.strip():
        log.info("Поиск: %s", args.query.strip())

    started_at = datetime.now()
    scrape_status = "ok"
    error_message: str | None = None
    coins: list[AtbCoin] = []

    try:
        coins = await scrape(args)
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
        "total_pages": 1,
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
    log.info("  Найдено монет : %s", len(coins))
    log.info("  Статус        : %s", scrape_status)
    log.info("=" * 60)
    return 0 if scrape_status == "ok" else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
