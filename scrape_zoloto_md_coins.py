#!/usr/bin/env python3
"""Скрейпер каталога монет Zoloto-MD без внешних библиотек."""
from __future__ import annotations

import argparse
import json
import logging
import re
import ssl
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen

BASE_URL = "https://spb.zoloto-md.ru"
CATALOG_PATH = "/catalog"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_zoloto_md_catalog.json"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.4
DEFAULT_LIMIT = 100
DEFAULT_AVAILABLE = 1

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("zoloto_md_scraper")


@dataclass
class Coin:
    name: str
    article: str | None
    url: str
    metal: str | None
    weight_g: float | None
    price: float | None

    def to_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v is not None}


def build_catalog_url(page: int, limit: int, available: int) -> str:
    return f"{BASE_URL}{CATALOG_PATH}?page={page}&limit={limit}&available={available}"


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
        return forms.get(m.group(1))
    if "медно-никелев" in n:
        return "медно-никелевый сплав"
    if "золотая монета" in n or "золотой жетон" in n:
        return "золото"
    if "серебряная монета" in n or "серебряный жетон" in n:
        return "серебро"
    return None


def parse_weight_g(name: str) -> float | None:
    n = name.casefold()
    m = re.search(r"(\d+(?:[.,]\d+)?)\s*(?:г\s*)?чистого", n)
    if not m:
        # Игнорируем "г.в." (год выпуска), берём именно массу.
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


def fetch_text(url: str, timeout_s: float, retries: int, insecure: bool, delay: float) -> str:
    headers = {"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8"}
    context = ssl._create_unverified_context() if insecure else None

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
        club_price_match = re.search(r'<span class="js-price-club">\s*([^<]+)\s*</span>', block, flags=re.I)

        if not href_match or not name_match:
            continue

        href = href_match.group(1).strip()
        url = urljoin(BASE_URL, href)
        if url in seen:
            continue

        name = strip_tags(name_match.group(1))
        standard_price = parse_price(price_match.group(1)) if price_match else None
        club_price = parse_price(club_price_match.group(1)) if club_price_match else None
        price = standard_price if standard_price is not None else club_price

        coin = Coin(
            name=name,
            article=id_match.group(1) if id_match else None,
            url=url,
            metal=normalize_metal(name),
            weight_g=parse_weight_g(name),
            price=price,
        )
        coins.append(coin)
        seen.add(url)

    return coins


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Скрейпер каталога монет spb.zoloto-md.ru")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Путь к выходному JSON")
    parser.add_argument("--start-page", type=int, default=1, help="Стартовая страница")
    parser.add_argument("--max-pages", type=int, default=0, help="Ограничение по страницам (0 = все)")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="Параметр limit в URL")
    parser.add_argument("--available", type=int, default=DEFAULT_AVAILABLE, help="Параметр available в URL")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="Таймаут HTTP-запроса, мс")
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="Количество попыток запроса")
    parser.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="Базовая пауза между retry, сек")
    parser.add_argument("--insecure", action="store_true", help="Отключить SSL-проверку (fallback)")
    parser.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        default="INFO",
        help="Уровень логирования",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )

    timeout_s = max(1.0, args.timeout / 1000.0)
    start_page = max(1, args.start_page)
    retries = max(1, args.retries)
    delay = max(0.0, args.delay)

    try:
        first_url = build_catalog_url(start_page, args.limit, args.available)
        log.info("Загружаю первую страницу: %s", first_url)
        first_html = fetch_text(first_url, timeout_s, retries, args.insecure, delay)

        total_pages = parse_total_pages(first_html, fallback=start_page)
        if args.max_pages > 0:
            total_pages = min(total_pages, start_page + args.max_pages - 1)
        log.info("Страниц для обхода: %s", total_pages - start_page + 1)

        all_coins: list[Coin] = []
        seen_urls: set[str] = set()

        for page in range(start_page, total_pages + 1):
            if page == start_page:
                html = first_html
            else:
                url = build_catalog_url(page, args.limit, args.available)
                log.info("Загружаю страницу %s: %s", page, url)
                html = fetch_text(url, timeout_s, retries, args.insecure, delay)

            page_coins = parse_coins_from_page(html)
            added = 0
            for coin in page_coins:
                if coin.url in seen_urls:
                    continue
                seen_urls.add(coin.url)
                all_coins.append(coin)
                added += 1
            log.info("Страница %s: найдено %s, добавлено %s", page, len(page_coins), added)

        payload = {
            "scraped_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "source": build_catalog_url(start_page, args.limit, args.available),
            "total_pages": total_pages - start_page + 1,
            "total_coins": len(all_coins),
            "coins": [coin.to_dict() for coin in all_coins],
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        log.info("Готово. Монет: %s. Файл: %s", len(all_coins), args.output)
        return 0
    except Exception as exc:  # noqa: BLE001
        log.error("%s", exc)
        return 2


if __name__ == "__main__":
    sys.exit(main())
