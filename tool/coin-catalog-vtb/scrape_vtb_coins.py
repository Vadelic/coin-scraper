#!/usr/bin/env python3
"""Скрейпер каталога монет ВТБ через BFF API (только стандартная библиотека).

POST https://www.vtb.ru/api/bff/api/v1/coin/list?page=N с телом {"filters":[]}.

Логика: сначала POST page=1 (короткая серия повторов), при провале —
GET витрины для cookies и повтор BFF (--skip-vitrine отключает прогрев).

Если блокировка антибота или некорректный ответ — scrape_vtb_coins_playwright.py.

Запуск: python3 scrape_vtb_coins.py [options]
Результат: coins_vtb_catalog.json (или путь из --output).
"""
from __future__ import annotations

import argparse
import json
import logging
import ssl
import sys
import time
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, HTTPSHandler, Request, build_opener

# ============================================================================
# Constants
# ============================================================================

BASE_SITE = "https://www.vtb.ru"
LIST_PATH = "/api/bff/api/v1/coin/list"
COIN_CATALOG_URL = (
    f"{BASE_SITE}/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/"
)

DEFAULT_OUTPUT = Path(__file__).parent / "coins_vtb_catalog.json"
DEFAULT_TIMEOUT = 45.0
DEFAULT_VITRINE_TIMEOUT = 18.0
DEFAULT_RETRIES = 3
DEFAULT_PAGE_DELAY = 0.35

DEFAULT_HEADERS_JSON = {
    "Content-Type": "application/json",
    "Accept": "application/json",
    "Origin": BASE_SITE,
    # Как после page.goto(COIN_CATALOG_URL) в Playwright — fetch с той же витрины.
    "Referer": COIN_CATALOG_URL,
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
}

log = logging.getLogger("vtb_scraper")


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
# HTTP (stdlib)
# ============================================================================


def _build_opener(*, insecure_ssl: bool = False):
    jar = CookieJar()
    cookie_handler = HTTPCookieProcessor(jar)
    if insecure_ssl:
        https_handler = HTTPSHandler(context=ssl._create_unverified_context())
        return build_opener(cookie_handler, https_handler)
    return build_opener(cookie_handler)


def _headers_vitrine_get() -> dict[str, str]:
    return {
        "User-Agent": DEFAULT_HEADERS_JSON["User-Agent"],
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept": (
            "text/html,application/xhtml+xml,application/xml;q=0.9,"
            "image/avif,image/webp,*/*;q=0.8"
        ),
        "Referer": f"{BASE_SITE}/",
    }


def _headers_list_post() -> dict[str, str]:
    h = dict(DEFAULT_HEADERS_JSON)
    h["Accept-Language"] = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
    h["Sec-Fetch-Site"] = "same-origin"
    h["Sec-Fetch-Mode"] = "cors"
    h["Sec-Fetch-Dest"] = "empty"
    return h


def _http_request(
    opener,
    *,
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout_s: float,
) -> tuple[int, bytes]:
    req = Request(url, data=body, headers=headers, method=method)
    try:
        with opener.open(req, timeout=timeout_s) as resp:
            return resp.getcode(), resp.read()
    except HTTPError as e:
        return e.code, e.read()


def _parse_json_body(code: int, raw: bytes, url: str) -> dict:
    text = raw.decode("utf-8", errors="replace").strip()
    if code != 200:
        snippet = text[:300]
        raise RuntimeError(f"HTTP {code} для {url}: {snippet!r}")
    low = text.lstrip().lower()
    if not low.startswith("{"):
        snippet = text[:300]
        raise RuntimeError(f"Не JSON объект от {url}: {snippet!r}")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        snippet = text[:300]
        raise RuntimeError(f"JSON ошибка для {url}: {e}; начало: {snippet!r}") from e
    if not isinstance(data, dict):
        raise RuntimeError("Ответ list: ожидался объект JSON")
    return data


def fetch_list_page_urllib(
    opener,
    page_num: int,
    *,
    timeout_s: float,
    retries: int,
) -> dict | None:
    url = build_list_url(page_num)
    payload = json.dumps(build_payload(), ensure_ascii=False).encode("utf-8")
    for attempt in range(1, retries + 1):
        try:
            code, raw = _http_request(
                opener,
                url=url,
                method="POST",
                headers=_headers_list_post(),
                body=payload,
                timeout_s=timeout_s,
            )
            return _parse_json_body(code, raw, url)
        except (RuntimeError, URLError, OSError) as e:
            log.warning(
                "HTTP: страница %s, попытка %s/%s — %s",
                page_num,
                attempt,
                retries,
                e,
            )
            if attempt < retries:
                time.sleep(2**attempt)
    return None


def _warmup_vitrine(opener, args: argparse.Namespace) -> None:
    """GET витрины с отдельным (коротким) таймаутом — для cookies."""
    vitrine_t = max(float(args.vitrine_timeout), 1.0)
    log.info(
        "Прогрев: GET витрины (таймаут %s с, до %s попыток)",
        vitrine_t,
        args.retries,
    )
    for v_attempt in range(1, args.retries + 1):
        try:
            code, raw = _http_request(
                opener,
                url=COIN_CATALOG_URL,
                method="GET",
                headers=_headers_vitrine_get(),
                body=None,
                timeout_s=vitrine_t,
            )
            if code == 200:
                log.debug("Витрина HTTP 200, ответ %s байт", len(raw))
                return
            log.warning(
                "Витрина HTTP %s (попытка %s/%s)",
                code,
                v_attempt,
                args.retries,
            )
        except (URLError, OSError) as e:
            log.warning(
                "Витрина недоступна (попытка %s/%s): %s",
                v_attempt,
                args.retries,
                e,
            )
        if v_attempt < args.retries:
            time.sleep(2**v_attempt)
    log.warning("Прогрев витрины не удался; продолжаем с текущими cookies")


def _fetch_first_list_page(
    opener,
    args: argparse.Namespace,
    timeout_s: float,
) -> dict | None:
    """POST page=1: короткая серия, при провале — опционально прогрев и полные повторы."""
    cold_retries = min(2, args.retries)
    log.info("POST coin/list page=1 (%s быстрых попыток)", cold_retries)
    body = fetch_list_page_urllib(
        opener,
        1,
        timeout_s=timeout_s,
        retries=cold_retries,
    )
    if body is not None:
        return body
    if not args.skip_vitrine:
        _warmup_vitrine(opener, args)
    else:
        log.info("Прогрев витрины пропущен (--skip-vitrine)")
    log.info("POST coin/list page=1 (%s попыток)", args.retries)
    return fetch_list_page_urllib(
        opener,
        1,
        timeout_s=timeout_s,
        retries=args.retries,
    )


def scrape_vtb(args: argparse.Namespace) -> tuple[int, list[dict[str, Any]]]:
    opener = _build_opener(insecure_ssl=args.insecure_ssl)
    timeout_s = max(float(args.timeout), 1.0)
    seen: set[str] = set()
    merged: list[dict[str, Any]] = []
    max_page_seen = 1

    body = _fetch_first_list_page(opener, args, timeout_s)
    if not body:
        log.error(
            "BFF coin/list недоступен после всех попыток; "
            "попробуйте scrape_vtb_coins_playwright.py"
        )
        return 1, merged

    page_num = 1
    while True:
        if page_num > 1:
            time.sleep(args.delay)
            log.info("Запрос списка page=%s", page_num)
            body = fetch_list_page_urllib(
                opener,
                page_num,
                timeout_s=timeout_s,
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

    return max_page_seen, merged


# ============================================================================
# CLI
# ============================================================================


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Скрейпер каталога монет ВТБ (BFF API), только stdlib",
    )
    p.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="итоговый JSON")
    p.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help="таймаут POST /coin/list, с (default: %(default)s)",
    )
    p.add_argument(
        "--vitrine-timeout",
        type=float,
        default=DEFAULT_VITRINE_TIMEOUT,
        help="таймаут GET витрины при прогреве, с (default: %(default)s)",
    )
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
        "--insecure-ssl",
        action="store_true",
        help=(
            "не проверять TLS (при CERTIFICATE_VERIFY_FAILED; небезопасно)"
        ),
    )
    p.add_argument(
        "--skip-vitrine",
        action="store_true",
        help="не делать GET витрины при провале первого POST (быстрее при блокировке HTML)",
    )
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


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет ВТБ (BFF), stdlib HTTP")
    log.info("=" * 60)
    started = datetime.now()

    max_page, coins = scrape_vtb(args)

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
    if not coins:
        log.warning(
            "Каталог пуст или недоступен; для обхода антибота см. scrape_vtb_coins_playwright.py"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
