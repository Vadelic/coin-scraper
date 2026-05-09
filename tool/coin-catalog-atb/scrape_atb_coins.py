#!/usr/bin/env python3
"""Скрейпер каталога монет ATB без внешних библиотек.

Источник:
https://www.atb.su/vklady-i-scheta/monety/

Получает HTML-фрагмент каталога через AJAX POST (параметр ajax=true),
парсит карточки монет и сохраняет JSON.
"""
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
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen

CATALOG_URL = "https://www.atb.su/vklady-i-scheta/monety/"
BASE_URL = "https://www.atb.su"
DEFAULT_OUTPUT = Path(__file__).parent / "coins_atb_catalog.json"
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_RETRIES = 3
DEFAULT_DELAY = 0.5

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("atb_scraper")


@dataclass
class AtbCoin:
    name: str
    article: str | None
    url: str
    metal: str | None
    weight: float | None
    price: float | None = None

    def to_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v is not None}


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
    s = text.strip().casefold()
    known = (
        "золото",
        "серебро",
        "платина",
        "палладий",
        "медно-никелевый сплав",
    )
    for metal in known:
        if metal in s:
            return metal
    m = re.match(r"[A-Za-zА-Яа-яЁё\- ]+", s)
    if not m:
        return None
    return m.group(0).strip() or None


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


def extract_field(block: str, label: str) -> str | None:
    pattern = rf"{re.escape(label)}\s*</span>\s*<span[^>]*>(.*?)</span>"
    m = re.search(pattern, block, flags=re.I | re.S)
    if not m:
        return None
    value = strip_tags(m.group(1))
    return value or None


def parse_detail_fields(detail_html: str) -> tuple[str | None, str | None, float | None]:
    rows = re.findall(r"<tr>\s*<td>(.*?)</td>\s*<td>(.*?)</td>\s*</tr>", detail_html, flags=re.I | re.S)
    kv: dict[str, str] = {}
    for key_html, value_html in rows:
        key = strip_tags(key_html)
        value = strip_tags(value_html)
        if key and value:
            kv[key.casefold()] = value

    article = kv.get("каталожный номер")
    metal = normalize_metal(kv.get("металл, проба") or kv.get("металл"))
    weight_raw = kv.get("масса общая, г") or kv.get("масса, г") or kv.get("масса")
    weight = parse_weight(weight_raw or "")
    return article, metal, weight


def fetch_detail_html(
    url: str,
    timeout_s: float,
    retries: int,
    insecure: bool,
    delay: float,
) -> str:
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
                    raise RuntimeError(f"Пустая detail-страница: {url}")
                return body
        except (HTTPError, URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(delay * (2 ** (attempt - 1)))

    raise RuntimeError(f"Не удалось получить detail-страницу {url}: {last_error}")


def parse_card(
    card_html: str,
    href: str,
    timeout_s: float,
    retries: int,
    insecure: bool,
    delay: float,
) -> AtbCoin | None:
    name_match = re.search(r'class="coins-item__name"\s*>(.*?)</div>', card_html, flags=re.S)
    if not name_match:
        return None
    name = strip_tags(name_match.group(1))
    if not name:
        return None

    price_match = re.search(r'class="coins-item__price"\s*>(.*?)</div>', card_html, flags=re.S)
    price = parse_price(strip_tags(price_match.group(1))) if price_match else None

    url = urljoin(BASE_URL, href)
    detail_html = fetch_detail_html(
        url=url,
        timeout_s=timeout_s,
        retries=retries,
        insecure=insecure,
        delay=delay,
    )
    article, metal, weight = parse_detail_fields(detail_html)

    return AtbCoin(
        name=name,
        article=article,
        url=url,
        metal=metal,
        weight=weight,
        price=price,
    )


def parse_coins(
    fragment_html: str,
    timeout_s: float,
    retries: int,
    insecure: bool,
    delay: float,
) -> list[AtbCoin]:
    cards = re.findall(
        r'<a\s+class="coins-item[^"]*"\s+href="(/vklady-i-scheta/monety/[^"]+/?)"[^>]*>(.*?)</a>',
        fragment_html,
        flags=re.S | re.I,
    )

    coins: list[AtbCoin] = []
    seen_urls: set[str] = set()
    for index, (href, card_html) in enumerate(cards, start=1):
        coin = parse_card(
            card_html,
            href,
            timeout_s=timeout_s,
            retries=retries,
            insecure=insecure,
            delay=delay,
        )
        if not coin:
            continue
        if coin.url in seen_urls:
            continue
        seen_urls.add(coin.url)
        coins.append(coin)
        if index % 20 == 0:
            log.info("Обработано карточек: %s/%s", index, len(cards))
    return coins


def build_request_payload() -> bytes:
    # count=99999 запрашивает "Все" из селектора "Показывать".
    form_data = (
        "ajax=true&"
        "category=&"
        "type=js-coins&"
        "country=&"
        "metall=&"
        "sample=&"
        "denomination=&"
        "year=&"
        "count=99999&"
        "name=&"
        "page=1"
    )
    return form_data.encode("utf-8")


def fetch_ajax_fragment(
    timeout_s: float,
    retries: int,
    insecure: bool,
    delay: float,
) -> str:
    payload = build_request_payload()
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html, */*;q=0.1",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin": BASE_URL,
        "Referer": CATALOG_URL,
        "X-Requested-With": "XMLHttpRequest",
    }
    context = ssl._create_unverified_context() if insecure else None

    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        req = Request(CATALOG_URL, data=payload, headers=headers, method="POST")
        try:
            with urlopen(req, timeout=timeout_s, context=context) as resp:
                body = resp.read().decode("utf-8", "ignore")
                if not body.strip():
                    raise RuntimeError("Пустой ответ от ATB AJAX endpoint")
                return body
        except (HTTPError, URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            log.warning("Попытка %s/%s: %s", attempt, retries, exc)
            if attempt < retries:
                time.sleep(delay * (2 ** (attempt - 1)))

    raise RuntimeError(f"Не удалось получить данные ATB: {last_error}")


def save_json(output: Path, coins: Iterable[AtbCoin]) -> None:
    payload = {
        "scraped_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source": CATALOG_URL,
        "total_coins": 0,
        "coins": [],
    }
    items = [coin.to_dict() for coin in coins]
    payload["coins"] = items
    payload["total_coins"] = len(items)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Скрейпер монет ATB (без внешних библиотек)")
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Путь к JSON-файлу результата (по умолчанию: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT_MS,
        help=f"Таймаут HTTP-запроса в мс (по умолчанию: {DEFAULT_TIMEOUT_MS})",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=DEFAULT_RETRIES,
        help=f"Число попыток запроса (по умолчанию: {DEFAULT_RETRIES})",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_DELAY,
        help=f"Базовая задержка между retry в секундах (по умолчанию: {DEFAULT_DELAY})",
    )
    parser.add_argument(
        "--insecure",
        action="store_true",
        help="Отключить SSL-валидацию (fallback для окружений с проблемным trust store)",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
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
    log.info("Старт скрейпера ATB")
    log.info("Источник: %s", CATALOG_URL)

    try:
        fragment = fetch_ajax_fragment(
            timeout_s=timeout_s,
            retries=max(1, args.retries),
            insecure=args.insecure,
            delay=max(0.0, args.delay),
        )
        coins = parse_coins(
            fragment,
            timeout_s=timeout_s,
            retries=max(1, args.retries),
            insecure=args.insecure,
            delay=max(0.0, args.delay),
        )
        if not coins:
            raise RuntimeError("Не удалось распарсить монеты из ответа")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        save_json(args.output, coins)
    except Exception as exc:  # noqa: BLE001
        log.error("%s", exc)
        return 2

    log.info("Собрано монет: %s", len(coins))
    log.info("Результат: %s", args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
