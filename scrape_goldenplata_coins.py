#!/usr/bin/env python3
"""Скрейпер инвестиционных монет goldenplata.ru без внешних библиотек."""
from __future__ import annotations

import argparse
import html
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

BASE_URL = "https://goldenplata.ru"
CATALOG_URL = f"{BASE_URL}/catalog/investitsionnye-monety/"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_goldenplata_catalog.json"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.4

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("goldenplata_scraper")


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


def fetch_text(url: str, timeout_s: float, retries: int, delay: float, insecure: bool) -> str:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer": CATALOG_URL,
    }
    context = ssl._create_unverified_context() if insecure else None
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        req = Request(url, headers=headers, method="GET")
        try:
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


def parse_price(value) -> float | None:
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
    forms = {
        "золото": "золото",
        "серебро": "серебро",
        "платина": "платина",
        "палладий": "палладий",
    }
    if s in forms:
        return forms[s]
    for src, dst in forms.items():
        if src in s:
            return dst
    return None


def parse_weight_g(text: str) -> float | None:
    # Берем массу перед "гр", чтобы не ловить "2025 г." (год выпуска).
    m = re.search(r"(\d+(?:[.,]\d+)?)\s*гр\.?", text.casefold())
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", "."))
    except ValueError:
        return None


def page_url(page: int) -> str:
    return CATALOG_URL if page == 1 else f"{CATALOG_URL}?PAGEN_4={page}"


def parse_total_pages(html_text: str) -> int:
    pages = [int(x) for x in re.findall(r"[?&]PAGEN_4=(\d+)", html_text, flags=re.I)]
    return max([1, *pages]) if pages else 1


def parse_coins_from_page(html_text: str) -> list[Coin]:
    coins: list[Coin] = []
    payloads = re.findall(
        r'<script type="application/json" class="js-analytics-payload">\s*(.*?)\s*</script>',
        html_text,
        flags=re.S,
    )
    hrefs = re.findall(
        r'<div class="gmsec6_item_name">\s*<a href="([^"]+)"[^>]*>',
        html_text,
        flags=re.S,
    )

    for i, raw_json in enumerate(payloads):
        try:
            data = json.loads(raw_json)
        except json.JSONDecodeError:
            continue
        name = html.unescape(str(data.get("item_name", "")).strip())
        item_id = str(data.get("item_id", "")).strip() or None
        if not name:
            continue

        href = hrefs[i] if i < len(hrefs) else ""
        url = urljoin(BASE_URL, href) if href else CATALOG_URL

        card_price = parse_price(data.get("cardprice"))
        price = card_price if card_price is not None else parse_price(data.get("price"))
        metal = normalize_metal(str(data.get("item_variant", "")).strip()) or normalize_metal(name)
        weight = parse_weight_g(name)

        coins.append(
            Coin(
                name=name,
                article=item_id,
                url=url,
                metal=metal,
                weight_g=weight,
                price=price,
            )
        )
    return coins


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Скрейпер GoldenPlata: инвестиционные монеты")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Путь к JSON-результату")
    parser.add_argument("--max-pages", type=int, default=0, help="Ограничение страниц (0 = все)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="Таймаут HTTP в мс")
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="Количество retry")
    parser.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="Базовая пауза retry, сек")
    parser.add_argument("--insecure", action="store_true", help="Отключить SSL-проверку")
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
    retries = max(1, args.retries)
    delay = max(0.0, args.delay)

    try:
        first_html = fetch_text(page_url(1), timeout_s, retries, delay, args.insecure)
        total_pages = parse_total_pages(first_html)
        if args.max_pages > 0:
            total_pages = min(total_pages, args.max_pages)
        log.info("Страниц к обходу: %s", total_pages)

        all_coins: list[Coin] = []
        seen_articles: set[str] = set()

        for page in range(1, total_pages + 1):
            url = page_url(page)
            html_text = first_html if page == 1 else fetch_text(url, timeout_s, retries, delay, args.insecure)
            page_coins = parse_coins_from_page(html_text)
            added = 0
            for coin in page_coins:
                key = coin.article or coin.url
                if key in seen_articles:
                    continue
                seen_articles.add(key)
                all_coins.append(coin)
                added += 1
            log.info("Страница %s: найдено %s, добавлено %s", page, len(page_coins), added)

        payload = {
            "scraped_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "source": CATALOG_URL,
            "total_pages": total_pages,
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
