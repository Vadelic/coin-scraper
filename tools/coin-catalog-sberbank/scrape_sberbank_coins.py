#!/usr/bin/env python3
"""Скрейпер каталога монет sberbank.ru (HTTP, stdlib).

Исключение из общего стандарта: для Сбера надёжнее прямой HTTP (GET витрины +
POST/GET API), чем Playwright — меньше ложных срабатываний WAF.

POST /proxy/services/coin-catalog/coins; по умолчанию четыре запроса по металлам,
merge по id, GET buyout для buy_price.

Итог: JSON в stdout. Поля монеты: catalog_number, name, metal, weight_g, buy_price,
sell_price. --investment-only → sections=«Инвестиционные монеты».

Запасной вариант при необходимости браузера: scrape_sberbank_coins_playwright.py.
"""
from __future__ import annotations

import argparse
import json
import logging
import ssl
import sys
import time
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime
from http.cookiejar import CookieJar
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from typing import Any
from urllib.request import HTTPCookieProcessor, HTTPSHandler, Request, build_opener


@dataclass
class SberCoin:
    name: str
    catalog_number: str | None = None
    metal: str | None = None
    weight_g: float | None = None
    buy_price: float | None = None
    sell_price: float | None = None
    _id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "catalog_number": self.catalog_number,
            "name": self.name,
            "metal": self.metal,
            "weight_g": self.weight_g,
            "buy_price": self.buy_price,
            "sell_price": self.sell_price,
        }

# ============================================================================
# Constants
# ============================================================================

CATALOG_URL = "https://www.sberbank.ru/ru/person/mon"
ORIGIN = "https://www.sberbank.ru"
API_PATH = "/proxy/services/coin-catalog/coins"
API_BUYOUT_PATH = "/proxy/services/coin-catalog/coins/buyout"

DEFAULT_PAGE_SIZE = 4000
DEFAULT_CITY = "Москва"
DEFAULT_CONDITION = 1
DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3

INVESTMENT_SECTION = "Инвестиционные монеты"

# Значения filters.metals как на витрине mon (один элемент в массиве на запрос).
DEFAULT_METAL_FILTERS: tuple[str, ...] = ("Золото", "Серебро", "Платина", "Палладий")

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

log = logging.getLogger("sberbank_scraper")


# ============================================================================
# Pure functions
# ============================================================================

def build_payload(
    *,
    page: int = 0,
    page_size: int = DEFAULT_PAGE_SIZE,
    city: str = DEFAULT_CITY,
    condition: int = DEFAULT_CONDITION,
    query: str = "",
    metals: Sequence[str] | None = None,
    sections: Sequence[str] | None = None,
    categories: Sequence[str] | None = None,
) -> dict:
    """Собирает тело POST-запроса к /proxy/services/coin-catalog/coins.

    Параметр metals: непустой список строк — фильтр по металлу(ам); None или []
    означает запрос без ограничения по металлу.
    """
    metals_list = list(metals) if metals else []
    return {
        "query": query,
        "priceSellMin": 0,
        "priceSellMax": 0,
        "parMin": 0,
        "parMax": 0,
        "massMin": 0,
        "massMax": 0,
        "metals": metals_list,
        "sections": list(sections) if sections else [],
        "categories": list(categories) if categories else [],
        "condition": condition,
        "vspCode": None,
        "inDiscount": False,
        "page": page,
        "pageSize": page_size,
        "city": city,
    }


def resolve_sections(args: argparse.Namespace) -> list[str]:
    if args.investment_only:
        return [INVESTMENT_SECTION]
    return []


def build_buyout_fetch_path(
    *,
    page: int = 0,
    page_size: int = DEFAULT_PAGE_SIZE,
    query: str = "",
) -> str:
    """Путь с query string для GET coin-catalog/coins/buyout (относительно origin)."""
    qs = urlencode({"query": query or "", "page": page, "pageSize": page_size})
    return f"{API_BUYOUT_PATH}?{qs}"


def buyout_price_from_buyout_entity(entity: dict) -> float | None:
    """Извлекает цену выкупа из записи ответа /coins/buyout (разные имена полей)."""
    for key in ("priceBuy", "buyoutPrice", "buyPrice", "price"):
        v = _to_float(entity.get(key))
        if v is not None:
            return v
    return None


def entities_from_buyout_response(data) -> list[dict]:
    """Достаёт список монет из JSON ответа GET /coins/buyout."""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in ("entities", "items", "data"):
            v = data.get(key)
            if isinstance(v, list):
                return v
    return []


def merge_entities_with_metal_filters(
    per_metal_responses: Iterable[tuple[str, list[dict]]],
) -> tuple[list[dict], int]:
    """Склеивает несколько выдач каталога с разными фильтрами metals.

    В каждую запись добавляется поле ``metal`` (метка фильтра). Один и тот же
    ``id`` из разных фильтров даёт одну запись: сохраняется первый металл по
    порядку итерации; конфликт пишется в лог уровня WARNING.

    Returns:
        (список копий entity с ключом metal, число обработанных строк из всех POST)
    """
    merged: list[dict] = []
    id_to_metal: dict[str, str] = {}
    raw_rows = 0
    for filter_label, rows in per_metal_responses:
        for row in rows:
            raw_rows += 1
            cid = row.get("id")
            if cid is None:
                continue
            sid = str(cid)
            if sid in id_to_metal:
                if id_to_metal[sid] != filter_label:
                    log.warning(
                        "Дубликат id=%s: уже металл «%s», в выдаче «%s» — оставляем первый",
                        sid,
                        id_to_metal[sid],
                        filter_label,
                    )
                continue
            id_to_metal[sid] = filter_label
            copy = dict(row)
            copy["metal"] = filter_label
            merged.append(copy)
    return merged, raw_rows


def merge_buyout_into_catalog(catalog: list[dict], buyout_rows: list[dict]) -> None:
    """Подставляет в объекты каталога priceBuy из списка выкупа (по id)."""
    prices: dict[str, float] = {}
    for row in buyout_rows:
        cid = row.get("id")
        if cid is None:
            continue
        p = buyout_price_from_buyout_entity(row)
        if p is not None:
            prices[str(cid)] = p
    for row in catalog:
        cid = row.get("id")
        if cid is None:
            continue
        sid = str(cid)
        if sid in prices:
            row["priceBuy"] = prices[sid]


def _to_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _entity_catalog_number(entity: dict, coin_id: str) -> str | None:
    for key in (
        "catalogNumber",
        "catalogNum",
        "cbrCatalogNumber",
        "article",
        "sku",
        "code",
    ):
        raw = entity.get(key)
        if raw is not None and str(raw).strip():
            return str(raw).strip()
    stripped = str(coin_id).strip()
    return stripped or None


def _metal_label_from_entity(entity: dict) -> str | None:
    """Нормализует метку металла (после merge — из фильтра; из API редко)."""
    raw = entity.get("metal")
    if raw is None:
        return None
    s = str(raw).strip()
    return s or None


def row_to_coin(entity: dict) -> SberCoin | None:
    coin_id = entity.get("id")
    if not coin_id:
        return None
    sid = str(coin_id).strip()
    name = (entity.get("name") or "").strip() or sid
    return SberCoin(
        name=name,
        catalog_number=_entity_catalog_number(entity, sid),
        metal=_metal_label_from_entity(entity),
        weight_g=_to_float(entity.get("mass1")),
        buy_price=_to_float(entity.get("priceBuy")),
        sell_price=_to_float(entity.get("price")),
        _id=sid,
    )


def coin_matches_query(coin: SberCoin, query: str) -> bool:
    q = query.casefold().strip()
    if not q:
        return True
    hay = " ".join(
        x
        for x in (coin.name, coin.catalog_number or "", coin.metal or "")
        if x
    ).casefold()
    return q in hay


def dedupe_key(_entity: dict, coin: SberCoin) -> str:
    if coin._id:
        return coin._id
    return coin.catalog_number or coin.name


# ============================================================================
# HTTP (stdlib)
# ============================================================================

def _timeout_seconds(timeout_ms: int) -> float:
    return max(timeout_ms, 1) / 1000.0


def _build_opener(*, insecure_ssl: bool = False) -> Any:
    jar = CookieJar()
    cookie_handler = HTTPCookieProcessor(jar)
    if insecure_ssl:
        https_handler = HTTPSHandler(context=ssl._create_unverified_context())
        return build_opener(cookie_handler, https_handler)
    return build_opener(cookie_handler)


def _absolute_url(path_or_url: str) -> str:
    if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
        return path_or_url
    return ORIGIN + path_or_url


def _headers_vitrine_get() -> dict[str, str]:
    return {
        "User-Agent": USER_AGENT,
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept": (
            "text/html,application/xhtml+xml,application/xml;q=0.9,"
            "image/avif,image/webp,*/*;q=0.8"
        ),
        "Referer": ORIGIN + "/",
    }


def _headers_api_json_post() -> dict[str, str]:
    return {
        "User-Agent": USER_AGENT,
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin": ORIGIN,
        "Referer": CATALOG_URL,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }


def _headers_api_json_get() -> dict[str, str]:
    return {
        "User-Agent": USER_AGENT,
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin": ORIGIN,
        "Referer": CATALOG_URL,
        "Accept": "application/json",
    }


def _http_request(
    opener: Any,
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


def _parse_json_body(code: int, raw: bytes, url: str) -> Any:
    text = raw.decode("utf-8", errors="replace").strip()
    if code != 200:
        snippet = text[:300]
        raise RuntimeError(f"HTTP {code} для {url}: {snippet!r}")

    ctype = ""
    low = text.lstrip().lower()
    if not low.startswith("{") and not low.startswith("["):
        snippet = text[:300]
        raise RuntimeError(f"Не JSON от {url} (content): {snippet!r}")

    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        snippet = text[:300]
        raise RuntimeError(f"JSON ошибка для {url}: {e}; начало: {snippet!r}") from e


def fetch_catalog(args: argparse.Namespace) -> tuple[list[dict], int]:
    """GET витрины (cookies), POST каталог, GET buyout; возвращает (entities, число POST)."""
    sections = resolve_sections(args)
    pages_processed = 0
    opener = _build_opener(insecure_ssl=not args.secure_ssl)
    timeout_s = _timeout_seconds(args.timeout)

    for attempt in range(1, args.retries + 1):
        try:
            log.info(
                "Открываю витрину %s (попытка %s/%s)",
                CATALOG_URL, attempt, args.retries,
            )
            code, raw = _http_request(
                opener,
                url=CATALOG_URL,
                method="GET",
                headers=_headers_vitrine_get(),
                body=None,
                timeout_s=timeout_s,
            )
            if code != 200:
                log.warning(
                    "Витрина вернула HTTP %s (ожидали cookie-сессию; продолжаем)",
                    code,
                )
            catalog_url_absolute = ORIGIN + API_PATH

            if args.investment_only:
                log.info("Фильтр sections=%s", sections)

            payload_common = dict(
                page_size=args.page_size,
                city=args.city,
                condition=args.condition,
                query=args.query,
                sections=sections,
            )

            if args.no_metal_filters:
                log.info(
                    "Каталог: один POST %s (metals=[], sections=%s, pageSize=%s, city=%s)",
                    API_PATH,
                    sections,
                    args.page_size,
                    args.city,
                )
                payload = build_payload(page=0, metals=[], **payload_common)
                log.debug("POST payload: %s", payload)
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                p_code, p_raw = _http_request(
                    opener,
                    url=catalog_url_absolute,
                    method="POST",
                    headers=_headers_api_json_post(),
                    body=body,
                    timeout_s=timeout_s,
                )
                data = _parse_json_body(p_code, p_raw, catalog_url_absolute)
                if not isinstance(data, dict):
                    raise RuntimeError("Ответ каталога: ожидался объект JSON")
                entities = data.get("entities") or []
                pages_processed = 1
                log.info("Получено entities: %s", len(entities))
            else:
                log.info(
                    "Каталог: серия POST по металлам %s (sections=%s, pageSize=%s, city=%s)",
                    DEFAULT_METAL_FILTERS,
                    sections,
                    args.page_size,
                    args.city,
                )
                per_metal: list[tuple[str, list[dict]]] = []
                for metal in DEFAULT_METAL_FILTERS:
                    log.info("POST metals=%s", [metal])
                    payload = build_payload(page=0, metals=[metal], **payload_common)
                    log.debug("POST payload: %s", payload)
                    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                    p_code, p_raw = _http_request(
                        opener,
                        url=catalog_url_absolute,
                        method="POST",
                        headers=_headers_api_json_post(),
                        body=body,
                        timeout_s=timeout_s,
                    )
                    data = _parse_json_body(p_code, p_raw, catalog_url_absolute)
                    if not isinstance(data, dict):
                        raise RuntimeError("Ответ каталога: ожидался объект JSON")
                    rows = data.get("entities") or []
                    pages_processed += 1
                    log.info("Металл «%s»: строк в ответе %s", metal, len(rows))
                    per_metal.append((metal, rows))
                entities, raw_row_count = merge_entities_with_metal_filters(
                    per_metal
                )
                log.info(
                    "После объединения по id: уникальных %s "
                    "(всего строк из всех POST: %s)",
                    len(entities),
                    raw_row_count,
                )

            buyout_path = build_buyout_fetch_path(
                page=0,
                page_size=args.page_size,
                query=args.query,
            )
            buyout_full = _absolute_url(buyout_path)
            try:
                short = buyout_full if len(buyout_full) <= 80 else buyout_full[:80] + "…"
                log.info("Запрос GET %s", short)
                g_code, g_raw = _http_request(
                    opener,
                    url=buyout_full,
                    method="GET",
                    headers=_headers_api_json_get(),
                    body=None,
                    timeout_s=timeout_s,
                )
                buyout_data = _parse_json_body(g_code, g_raw, buyout_full)
                buyout_list = entities_from_buyout_response(buyout_data)
                log.info("Выкуп: записей %s", len(buyout_list))
                merge_buyout_into_catalog(entities, buyout_list)
            except (RuntimeError, URLError, OSError) as e:
                log.warning(
                    "Выкуп (GET buyout) недоступен, цены выкупа только из каталога: %s",
                    e,
                )

            return entities, pages_processed
        except (RuntimeError, URLError, OSError) as e:
            log.warning(
                "Попытка %s/%s — ошибка: %s",
                attempt,
                args.retries,
                e,
            )
            if attempt < args.retries:
                time.sleep(2**attempt)

    log.error("Все попытки исчерпаны")
    return [], 0


# ============================================================================
# Orchestration
# ============================================================================

def scrape(args: argparse.Namespace) -> tuple[int, list[SberCoin]]:
    """Скачивает каталог, парсит в SberCoin, дедуп по id."""
    entities, pages_processed = fetch_catalog(args)
    coins: list[SberCoin] = []
    seen: set[str] = set()
    url_query = (args.query or "").strip()

    for entity in entities:
        coin = row_to_coin(entity)
        if coin is None:
            log.warning("Пропуск записи: нет id")
            continue
        if not coin.name:
            log.warning(
                "Пропуск записи: пустое name, catalog_number=%s",
                coin.catalog_number,
            )
            continue
        if url_query and not coin_matches_query(coin, url_query):
            continue
        key = dedupe_key(entity, coin)
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

    return pages_processed, coins


# ============================================================================
# CLI
# ============================================================================

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Скрейпер каталога монет sberbank.ru (HTTP)",
    )
    p.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help="pageSize в payload (default: %(default)s)",
    )
    p.add_argument("--city", default=DEFAULT_CITY,
                   help="город для запроса (default: %(default)s)")
    p.add_argument(
        "--condition",
        type=int,
        default=DEFAULT_CONDITION,
        help="состояние монет (default: %(default)s)",
    )
    p.add_argument("--query", default="",
                   help="строка поиска (default: пусто)")
    p.add_argument(
        "--investment-only",
        action="store_true",
        help=f"только инвестиционные монеты (sections=«{INVESTMENT_SECTION}»)",
    )
    p.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT_MS,
        help="таймаут HTTP-запросов, мс (default: %(default)s)",
    )
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES,
                   help="попыток на запрос (default: %(default)s)")
    p.add_argument(
        "--secure-ssl",
        action="store_true",
        help=(
            "проверять TLS-сертификат сервера (по умолчанию проверка отключена — "
            "типично для Python на macOS без корневых сертификатов)"
        ),
    )
    p.add_argument(
        "--no-metal-filters",
        action="store_true",
        help=(
            "один POST с metals=[] без разбиения по Золото/Серебро/… "
            "(быстрее; metal в JSON только если был в ответе API)"
        ),
    )
    p.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="уровень логирования (default: %(default)s)",
    )
    return p


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
    log.info("  Скрейпер каталога монет sberbank.ru (HTTP)")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    total_pages = 0
    coins: list[SberCoin] = []

    try:
        total_pages, coins = scrape(args)
        if total_pages == 0 and not coins:
            scrape_status = "error"
            error_message = "Не удалось загрузить каталог (0 запросов к API)"
    except (RuntimeError, URLError, OSError) as e:
        scrape_status = "error"
        error_message = str(e)
        log.error("Ошибка HTTP: %s", e)

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
    log.info("  Запросов POST  : %s", total_pages)
    log.info("  Найдено монет  : %s", len(coins))
    log.info("  Статус         : %s", scrape_status)
    log.info("=" * 60)
    return 0 if scrape_status == "ok" else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
