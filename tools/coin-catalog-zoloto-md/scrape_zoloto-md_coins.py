#!/usr/bin/env python3
"""Скрейпер каталога монет spb.zoloto-md.ru (HTTP, stdlib).

Legacy-исключение: витрина отдаётся статическим HTML, Playwright не требуется.
Итог: JSON в stdout по coins_catalog.schema.json.
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import ssl
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urljoin
from urllib.request import Request, urlopen

BASE_URL = "https://spb.zoloto-md.ru"
CATALOG_PATH = "/catalog"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.4
DEFAULT_LIMIT = 100
COUNTRY_RUSSIA = "Россия"

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

METAL_LABELS = {
    "золото": "Золото",
    "серебро": "Серебро",
    "платина": "Платина",
    "палладий": "Палладий",
    "медно-никелевый сплав": "Медно-никелевый сплав",
}

log = logging.getLogger("zoloto-md_scraper")


@dataclass
class Coin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _url: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }


def build_catalog_url(
    page: int,
    *,
    limit: int,
    query: str = "",
    investment_only: bool = False,
) -> str:
    params: dict[str, str | int] = {
        "page": page,
        "limit": limit,
        "available": 1,
    }
    if investment_only:
        params["country"] = COUNTRY_RUSSIA
    if query.strip():
        params["query"] = query.strip()
    return f"{BASE_URL}{CATALOG_PATH}?{urlencode(params, quote_via=quote)}"


def parse_price(text: str) -> float | None:
    if not text:
        return None
    m = re.search(r"\d[\d\s]*(?:[.,]\d+)?", text.replace("\u00a0", " ").replace("\u202f", " "))
    if not m:
        return None
    raw = m.group(0).replace(" ", "").replace(",", ".")
    try:
        return float(raw)
    except ValueError:
        return None


def normalize_metal(name: str) -> str | None:
    n = name.casefold()
    m = re.search(r"чистого\s+(золота|серебра|платины|палладия)", n)
    if m:
        forms = {
            "золота": "золото",
            "серебра": "серебро",
            "платины": "платина",
            "палладия": "палладий",
        }
        key = forms.get(m.group(1))
        return METAL_LABELS.get(key, key) if key else None
    if "медно-никелев" in n:
        return METAL_LABELS["медно-никелевый сплав"]
    if "золотая монета" in n or "золотой жетон" in n:
        return METAL_LABELS["золото"]
    if "серебряная монета" in n or "серебряный жетон" in n:
        return METAL_LABELS["серебро"]
    return None


def parse_weight_g(name: str) -> float | None:
    n = name.casefold()
    m = re.search(r"(\d+(?:[.,]\d+)?)\s*(?:г\s*)?чистого", n)
    if not m:
        m = re.search(r"(\d+(?:[.,]\d+)?)\s*г(?!\s*\.?\s*в\b)", n)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", "."))
    except ValueError:
        return None


def strip_tags(text: str) -> str:
    s = re.sub(r"<[^>]+>", " ", text)
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
    return re.sub(r"\s+", " ", s).strip()


def parse_total_pages(html: str, fallback: int = 1) -> int:
    pages = [int(x) for x in re.findall(r"[?&]page=(\d+)", html)]
    return max([fallback, *pages]) if pages else fallback


def fetch_text(url: str, timeout_s: float, retries: int, secure_ssl: bool, delay: float) -> str:
    headers = {"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8"}
    context = None if secure_ssl else ssl._create_unverified_context()

    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            req = Request(url, headers=headers, method="GET")
            with urlopen(req, timeout=timeout_s, context=context) as resp:
                body = resp.read().decode("utf-8", "ignore")
                if not body.strip():
                    raise RuntimeError("Пустой HTTP-ответ")
                return body
        except (HTTPError, URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            log.warning("Попытка %s/%s для %s: %s", attempt, retries, url, exc)
            if attempt < retries:
                time.sleep(delay * (2 ** (attempt - 1)))

    raise RuntimeError(f"Не удалось загрузить {url}: {last_error}")


def parse_coins_from_page(html: str) -> list[Coin]:
    coins: list[Coin] = []
    seen: set[str] = set()
    blocks = html.split("<!-- product -->")
    for block in blocks:
        if "js-product product-list_item" not in block:
            continue

        id_match = re.search(r'data-id="(\d+)"', block)
        href_match = re.search(r'<a[^>]+class="pi-link-dark"[^>]+href="([^"]+)"', block, flags=re.I)
        name_match = re.search(r'<a[^>]+class="pi-link-dark"[^>]*>.*?<p>(.*?)</p>', block, flags=re.I | re.S)
        price_match = re.search(r'<span class="js-price">\s*([^<]+)\s*</span>', block, flags=re.I)
        buyout_match = re.search(
            r'<span class="js-price-buyout">\s*([^<]+)\s*</span>', block, flags=re.I
        )

        if not href_match or not name_match:
            continue

        href = href_match.group(1).strip()
        url = urljoin(BASE_URL, href)
        if url in seen:
            log.warning("Пропуск дубликата: %s", url)
            continue

        name = strip_tags(name_match.group(1))
        if not name:
            log.warning("Пропуск карточки без названия: %s", url)
            continue

        sell_price = parse_price(price_match.group(1)) if price_match else None
        buy_price = parse_price(buyout_match.group(1)) if buyout_match else None

        coin = Coin(
            name=name,
            catalog_number=id_match.group(1) if id_match else None,
            metal=normalize_metal(name),
            weight_g=parse_weight_g(name),
            buy_price=buy_price,
            sell_price=sell_price,
            _url=url,
        )
        coins.append(coin)
        seen.add(url)

    return coins


def scrape_catalog(args: argparse.Namespace) -> tuple[int, list[Coin]]:
    timeout_s = max(1.0, args.timeout / 1000.0)
    start_page = max(1, args.start_page)
    retries = max(1, args.retries)
    delay = max(0.0, args.delay)

    query = (args.query or "").strip()
    first_url = build_catalog_url(
        start_page,
        limit=args.limit,
        query=query,
        investment_only=args.investment_only,
    )
    log.info("Загружаю первую страницу: %s", first_url)
    first_html = fetch_text(first_url, timeout_s, retries, args.secure_ssl, delay)

    total_pages = parse_total_pages(first_html, fallback=start_page)
    if args.max_pages > 0:
        total_pages = min(total_pages, start_page + args.max_pages - 1)
    pages_count = total_pages - start_page + 1
    log.info("Страниц для обхода: %s", pages_count)

    all_coins: list[Coin] = []
    seen_urls: set[str] = set()

    for page in range(start_page, total_pages + 1):
        if page == start_page:
            html = first_html
        else:
            url = build_catalog_url(
                page,
                limit=args.limit,
                query=query,
                investment_only=args.investment_only,
            )
            log.info("Загружаю страницу %s: %s", page, url)
            html = fetch_text(url, timeout_s, retries, args.secure_ssl, delay)

        page_coins = parse_coins_from_page(html)
        added = 0
        for coin in page_coins:
            if coin._url in seen_urls:
                continue
            seen_urls.add(coin._url)
            all_coins.append(coin)
            added += 1
        log.info("Страница %s: найдено %s, добавлено %s", page, len(page_coins), added)

    with_buy = sum(1 for c in all_coins if c.buy_price is not None)
    log.info("Цена выкупа: %s из %s монет", with_buy, len(all_coins))

    return pages_count, all_coins


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Скрейпер каталога монет spb.zoloto-md.ru")
    parser.add_argument("--query", default="", help="Поиск на витрине (параметр query в URL каталога)")
    parser.add_argument(
        "--investment-only",
        action="store_true",
        help="Фильтр country=Россия на витрине (только монеты эмитента Россия)",
    )
    parser.add_argument("--start-page", type=int, default=1, help="Стартовая страница")
    parser.add_argument("--max-pages", type=int, default=0, help="Ограничение по страницам (0 = все)")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="Параметр limit в URL")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="Таймаут HTTP-запроса, мс")
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="Количество попыток запроса")
    parser.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="Базовая пауза между retry, сек")
    parser.add_argument(
        "--secure-ssl",
        action="store_true",
        help="Включить проверку TLS-сертификата (по умолчанию отключена)",
    )
    parser.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        default="INFO",
        help="Уровень логирования",
    )
    return parser


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stderr,
    )


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет spb.zoloto-md.ru (HTTP)")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[Coin] = []

    try:
        total_pages, coins = scrape_catalog(args)
        if not coins and not (args.query and args.query.strip()):
            scrape_status = "error"
            error_message = "Каталог пуст"
    except (RuntimeError, HTTPError, URLError, OSError) as exc:
        scrape_status = "error"
        error_message = str(exc)
        log.error("Ошибка сбора: %s", exc)

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
    log.info("  Страниц       : %s", total_pages)
    log.info("  Найдено монет : %s", len(coins))
    log.info("  Статус        : %s", scrape_status)
    log.info("=" * 60)
    return 0 if scrape_status == "ok" else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
