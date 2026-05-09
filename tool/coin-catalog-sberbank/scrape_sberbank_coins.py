#!/usr/bin/env python3
"""Скрейпер каталога монет sberbank.ru (только стандартная библиотека Python).

Получает каталог через POST /proxy/services/coin-catalog/coins (по умолчанию
четыре запроса с фильтром metals: Золото, Серебро, Платина, Палладий), объединяет
выдачу по id с полем metal, затем дополняет ценами выкупа из GET
/proxy/services/coin-catalog/coins/buyout. HTTP: GET витрины для cookies, затем
POST/GET к API с заголовками Origin/Referer.

При блокировках, капче или HTML вместо JSON используйте
scrape_sberbank_coins_playwright.py (Playwright).

При ошибке SSL CERTIFICATE_VERIFY_FAILED (нет корней в системе) можно передать флаг
--insecure-ssl (небезопасно) или установить системные сертификаты для Python.

Запуск: python3 scrape_sberbank_coins.py [options]
Результат: coins_sberbank_catalog.json (или путь из --output); у каждой монеты
в JSON есть metal (из фильтра запроса), catalog_number, buyout_price при наличии.
"""
from __future__ import annotations

import argparse
import json
import logging
import ssl
import sys
import time
from collections.abc import Iterable, Sequence
from dataclasses import asdict, dataclass
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from typing import Any
from urllib.request import HTTPCookieProcessor, HTTPSHandler, Request, build_opener


@dataclass
class Coin:
    url: str
    name: str
    catalog_number: str | None = None
    sku: str | None = None
    price: float | None = None
    buyout_price: float | None = None
    nominal: str | None = None
    metal: str | None = None
    purity: str | None = None
    weight_g: float | None = None
    mintage: str | None = None

    def to_dict(self) -> dict:
        # Совместимо с предыдущим форматом: ключи с None опускаем.
        return {k: v for k, v in asdict(self).items() if v is not None}

# ============================================================================
# Constants
# ============================================================================

CATALOG_URL = "https://www.sberbank.ru/ru/person/mon"
ORIGIN = "https://www.sberbank.ru"
COIN_URL_TEMPLATE = "https://www.sberbank.ru/ru/person/mon#/coin/{coin_id}"
API_PATH = "/proxy/services/coin-catalog/coins"
API_BUYOUT_PATH = "/proxy/services/coin-catalog/coins/buyout"

DEFAULT_OUTPUT = Path(__file__).parent / "coins_sberbank_catalog.json"
DEFAULT_PAGE_SIZE = 4000
DEFAULT_CITY = "Москва"
DEFAULT_CONDITION = 1
DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3

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
        "sections": [],
        "categories": [],
        "condition": condition,
        "vspCode": None,
        "inDiscount": False,
        "page": page,
        "pageSize": page_size,
        "city": city,
    }


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


def build_coin_url(coin_id: str) -> str:
    """Формирует ссылку на карточку монеты в SPA."""
    return COIN_URL_TEMPLATE.format(coin_id=coin_id)


def _to_float(value) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _entity_catalog_number(entity: dict, coin_id: str) -> str:
    """Каталожный номер из ответа API либо id монеты (артикул витрины Сбера)."""
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
    return str(coin_id).strip()


def _metal_label_from_entity(entity: dict) -> str | None:
    """Нормализует метку металла (после merge — из фильтра; из API редко)."""
    raw = entity.get("metal")
    if raw is None:
        return None
    s = str(raw).strip()
    return s or None


def parse_entity(entity: dict) -> Coin | None:
    """Приводит entity из API к унифицированному Coin.

    Возвращает None, если у объекта нет id (без него не построить url).
    Поля nominal/purity/mintage в ответе каталога обычно отсутствуют.
    metal — подставляется скрейпером при запросах с фильтром ``metals``.
    catalog_number — номер по каталогу/артикул из API или то же значение, что id.
    buyout_price — из поля priceBuy (после слияния с GET /coins/buyout при наличии).
    """
    coin_id = entity.get("id")
    if not coin_id:
        return None
    return Coin(
        url=build_coin_url(coin_id),
        name=(entity.get("name") or "").strip() or coin_id,
        catalog_number=_entity_catalog_number(entity, str(coin_id)),
        price=_to_float(entity.get("price")),
        buyout_price=_to_float(entity.get("priceBuy")),
        metal=_metal_label_from_entity(entity),
        weight_g=_to_float(entity.get("mass1")),
    )


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


def fetch_catalog(args: argparse.Namespace) -> list[dict]:
    """GET витрины (cookies), POST каталог, GET buyout; возвращает entities."""
    opener = _build_opener(insecure_ssl=args.insecure_ssl)
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

            if args.no_metal_filters:
                log.info(
                    "Каталог: один POST %s (metals=[], pageSize=%s, city=%s)",
                    API_PATH,
                    args.page_size,
                    args.city,
                )
                payload = build_payload(
                    page=0,
                    page_size=args.page_size,
                    city=args.city,
                    condition=args.condition,
                    query=args.query,
                )
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
                log.info("Получено entities: %s", len(entities))
            else:
                log.info(
                    "Каталог: серия POST по металлам %s (pageSize=%s, city=%s)",
                    DEFAULT_METAL_FILTERS,
                    args.page_size,
                    args.city,
                )
                per_metal: list[tuple[str, list[dict]]] = []
                for metal in DEFAULT_METAL_FILTERS:
                    log.info("POST metals=%s", [metal])
                    payload = build_payload(
                        page=0,
                        page_size=args.page_size,
                        city=args.city,
                        condition=args.condition,
                        query=args.query,
                        metals=[metal],
                    )
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

            return entities
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
    return []


# ============================================================================
# Orchestration
# ============================================================================

def scrape(args: argparse.Namespace) -> list[Coin]:
    """Скачивает каталог через ``fetch_catalog``, парсит в ``Coin``, дедуп по url."""
    entities = fetch_catalog(args)
    coins: list[Coin] = []
    seen: set[str] = set()
    for entity in entities:
        coin = parse_entity(entity)
        if coin is None or coin.url in seen:
            continue
        seen.add(coin.url)
        coins.append(coin)
    return coins


# ============================================================================
# CLI
# ============================================================================

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Скрейпер каталога монет sberbank.ru",
    )
    p.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="путь к итоговому JSON (default: %(default)s)",
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
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT_MS,
        help="таймаут HTTP-запросов, мс (default: %(default)s)",
    )
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES,
                   help="попыток на запрос (default: %(default)s)")
    p.add_argument(
        "--insecure-ssl",
        action="store_true",
        help=(
            "не проверять TLS-сертификат сервера (если ошибка CERTIFICATE_VERIFY_FAILED; "
            "небезопасно, только при отсутствии корневых сертификатов в системе)"
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
    )


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    configure_logging(args.log_level)

    log.info("=" * 60)
    log.info("  Скрейпер каталога монет sberbank.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    coins = scrape(args)

    result = {
        "scraped_at": started_at.isoformat(),
        "total_pages": 1,
        "total_coins": len(coins),
        "coins": [c.to_dict() for c in coins],
    }
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    log.info("=" * 60)
    log.info("  Найдено монет : %s", len(coins))
    log.info("  Результат     : %s", args.output)
    log.info("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
