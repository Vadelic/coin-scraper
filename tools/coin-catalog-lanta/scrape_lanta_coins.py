#!/usr/bin/env python3
"""Скрейпер каталога монет lanta.ru (Санкт-Петербург).

Источник: https://www.lanta.ru/petersburg/metals/coins/
Сбор через Playwright (антибот/CAPTCHA и динамический рендер).

Зависимости: pip install playwright; системный Google Chrome или Microsoft Edge.
playwright install chromium не обязателен.

Итог: JSON в stdout (один объект, без записи файлов).
Поля монеты: catalog_number (артикул), name, metal, weight_g, buy_price, sell_price.
Опционально: --query для поля «Найти»; --investment-only — раздел инвестиционных монет.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import sys
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from playwright.async_api import Error as PlaywrightError, Route, async_playwright

# ============================================================================
# Constants
# ============================================================================

BASE_URL = "https://www.lanta.ru"
CATALOG_URL = "https://www.lanta.ru/petersburg/metals/coins/"
INVESTMENT_CATALOG_URL = (
    "https://www.lanta.ru/petersburg/metals/coins/ivesticyonnie-monety/"
)
DEFAULT_DELAY = 0.5
DEFAULT_TIMEOUT_MS = 60_000
DEFAULT_RETRIES = 3
DEFAULT_SCROLL_PASSES = 8

# stylesheet не блокируем — иначе lanta.ru уходит в редирект/CAPTCHA.
BLOCKED_RESOURCE_TYPES = frozenset({"image", "media", "font"})
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

COIN_ITEM_SELECTOR = "li.js-openCoinLightbox"
COIN_LIST_SELECTOR = ".coinList"
POPUP_PATH = "/__ajax/coinPopup.php"

SEARCH_INPUT_SELECTOR = 'input[name="keywords"]'
BROWSER_CHANNELS = ("chrome", "msedge", "chromium")
LAUNCH_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"]

ARTICLE_RE = re.compile(
    r"артикул\s*[:：]\s*([0-9A-Za-zА-Яа-я\-–—/]+)",
    re.IGNORECASE,
)
METAL_LINE_RE = re.compile(
    r"^(золото|серебро|платина|палладий)\b",
    re.IGNORECASE,
)
WEIGHT_INFO_RE = re.compile(
    r"масса\s+(?:ag|au|монеты)\s*[:：]\s*(\d+(?:[,.]\d+)?)\s*г",
    re.IGNORECASE,
)
OUT_OF_STOCK_MARKERS = ("нет в продаже", "нет в наличии")

CAPTCHA_TITLE_HINTS = ("captcha",)
CAPTCHA_BODY_HINTS = (
    "не робот",
    "not a robot",
    "you're not a robot",
    "you’re not a robot",
    "ползунк",
    "выровнять картинку",
    "align the image",
)

log = logging.getLogger("lanta_scraper")


class CaptchaBlockedError(RuntimeError):
    """Сайт отдал страницу CAPTCHA вместо каталога."""


# ============================================================================
# Model
# ============================================================================


@dataclass
class LantaCoin:
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


# ============================================================================
# Pure helpers
# ============================================================================


def parse_price(text: str) -> float | None:
    """'1 234 567 ₽' -> 1234567.0."""
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
    """'7,78 г' -> 7.78."""
    if not text:
        return None
    m = re.search(r"\b(\d+(?:[,.]\d+)?)\s*г(?:р|рамм)?\b", text.casefold())
    return float(m.group(1).replace(",", ".")) if m else None


def _normalize(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).casefold()


def parse_article_from_popup_html(html: str) -> str | None:
    text = re.sub(r"<[^>]+>", " ", html)
    m = ARTICLE_RE.search(text)
    return m.group(1).strip() if m else None


def metal_from_info_lines(lines: list[str]) -> str | None:
    for line in lines:
        m = METAL_LINE_RE.match(line.strip())
        if m:
            name = m.group(1)
            return name[0].upper() + name[1:].lower()
    return None


def weight_g_from_info_lines(lines: list[str]) -> float | None:
    for line in lines:
        m = WEIGHT_INFO_RE.search(line)
        if m:
            return float(m.group(1).replace(",", "."))
    for line in lines:
        w = parse_weight_g(line)
        if w is not None:
            return w
    return None


def parse_list_prices(
    sell_raw: str,
    buy_raw: str,
    *,
    out_of_stock: bool,
) -> tuple[float | None, float | None]:
    buy_price = parse_price(buy_raw) if buy_raw else None
    sell_price: float | None = None
    if sell_raw and not any(m in _normalize(sell_raw) for m in OUT_OF_STOCK_MARKERS):
        sell_price = parse_price(sell_raw)
    if out_of_stock:
        sell_price = None
    return buy_price, sell_price


def list_item_to_coin(item: dict, catalog_number: str | None) -> LantaCoin | None:
    name = (item.get("name") or "").strip()
    if not name:
        return None
    info = item.get("info") or []
    if not isinstance(info, list):
        info = []
    info_lines = [str(x).strip() for x in info if str(x).strip()]
    buy_price, sell_price = parse_list_prices(
        item.get("sellRaw") or "",
        item.get("buyRaw") or "",
        out_of_stock=bool(item.get("out")),
    )
    return LantaCoin(
        name=name,
        catalog_number=catalog_number,
        metal=metal_from_info_lines(info_lines),
        weight_g=weight_g_from_info_lines(info_lines),
        buy_price=buy_price,
        sell_price=sell_price,
    )


def dedupe_key(item: dict, coin: LantaCoin) -> str:
    """Ключ дедупликации: data-id карточки, иначе артикул или fallback."""
    coin_id = item.get("id")
    if coin_id is not None and str(coin_id).strip():
        return f"id:{coin_id}"
    if coin.catalog_number:
        return f"art:{coin.catalog_number}"
    return f"fb:{coin.name}|{coin.weight_g}|{coin.metal}"


# ============================================================================
# Playwright helpers
# ============================================================================


async def page_is_captcha(page) -> bool:
    """True, если вместо каталога показана антибот-страница."""
    title = (await page.title()).casefold()
    if any(h in title for h in CAPTCHA_TITLE_HINTS):
        return True
    try:
        body = (await page.inner_text("body", timeout=5_000))[:4_000].casefold()
    except PlaywrightError:
        return False
    return any(h in body for h in CAPTCHA_BODY_HINTS)


async def wait_captcha_cleared(page, timeout_sec: int, poll_sec: float = 1.0) -> bool:
    """Ждёт, пока CAPTCHA исчезнет (ручное прохождение в headful)."""
    deadline = asyncio.get_event_loop().time() + timeout_sec
    while asyncio.get_event_loop().time() < deadline:
        if not await page_is_captcha(page):
            return True
        await asyncio.sleep(poll_sec)
    return not await page_is_captcha(page)


async def launch_chromium_browser(
    pw,
    *,
    headless: bool,
    browser_channel: str | None = None,
):
    """Запускает Chromium: системный Chrome/Edge или bundled Playwright."""
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
    """Блокирует тяжелые ресурсы для ускорения."""
    if route.request.resource_type in BLOCKED_RESOURCE_TYPES:
        await route.abort()
    else:
        await route.continue_()


async def apply_catalog_search(page, query: str) -> None:
    """Вводит строку в поле «Поиск» (name=keywords) и отправляет форму."""
    log.info("Поиск по запросу: «%s»", query)
    search_input = page.locator(SEARCH_INPUT_SELECTOR)
    await search_input.wait_for(state="visible", timeout=30_000)

    await search_input.click()
    await search_input.fill("")
    await search_input.fill(query)

    form = search_input.locator("xpath=ancestor::form[1]")
    submit = form.locator('button[type="submit"]').first
    if await submit.count() > 0:
        await submit.click()
    else:
        await search_input.press("Enter")

    await page.wait_for_timeout(2_000)
    await page.wait_for_selector(COIN_LIST_SELECTOR, timeout=30_000)

    prev_count = -1
    for _ in range(4):
        count = await page.locator(COIN_ITEM_SELECTOR).count()
        if count == prev_count:
            break
        prev_count = count
        await asyncio.sleep(0.5)


async def scroll_until_stable(page, passes: int, delay: float) -> None:
    """Прокручивает страницу, пока количество карточек стабильно."""
    js_count = """
() => document.querySelectorAll(%s).length
""" % json.dumps(COIN_ITEM_SELECTOR)

    stable = 0
    prev = -1
    for _ in range(max(1, passes)):
        await page.mouse.wheel(0, 3000)
        await asyncio.sleep(delay)
        try:
            cur = await page.evaluate(js_count)
        except PlaywrightError as e:
            log.debug("scroll_until_stable: evaluate пропущен (%s)", e)
            await asyncio.sleep(delay)
            continue
        if cur == prev:
            stable += 1
        else:
            stable = 0
        prev = cur
        if stable >= 2:
            break


COLLECT_LIST_JS = """
() => [...document.querySelectorAll(%s)].map(li => {
  const cont = li.querySelector('.coinList-cont');
  const priceEl = cont?.querySelector('.coinList-price');
  let sellRaw = '';
  if (priceEl) {
    const clone = priceEl.cloneNode(true);
    clone.querySelectorAll('small').forEach(s => s.remove());
    sellRaw = (clone.innerText || '').replace(/\\s+/g, ' ').trim();
  }
  const buyRaw = priceEl?.querySelector('small')?.innerText?.trim() || '';
  const info = [...(cont?.querySelectorAll('.coinList-infoFull li') || [])]
    .map(el => (el.innerText || '').replace(/\\s+/g, ' ').trim())
    .filter(Boolean);
  return {
    id: li.getAttribute('data-id'),
    formId: li.getAttribute('data-form-id') || '892',
    name: cont?.querySelector('.coinList-title')?.innerText?.trim() || '',
    sellRaw,
    buyRaw,
    out: priceEl?.classList.contains('out') || false,
    info,
  };
})
""" % json.dumps(COIN_ITEM_SELECTOR)


async def collect_list_items(page) -> list[dict]:
    """Собирает карточки из блока .coinList."""
    await page.wait_for_selector(COIN_LIST_SELECTOR, timeout=30_000)
    rows = await page.evaluate(COLLECT_LIST_JS)
    return [r for r in rows if isinstance(r, dict)]


async def fetch_popup_html(page, coin_id: str, form_id: str) -> str:
    """Загружает HTML попапа монеты (артикул и детали)."""
    return await page.evaluate(
        """
        async ({ path, id, formId }) => {
          const qs = new URLSearchParams({
            id: String(id),
            formId: String(formId),
            set: 'N',
            clear_cache: 'Y',
          });
          const r = await fetch(`${path}?${qs}`, {
            credentials: 'include',
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
          });
          if (!r.ok) throw new Error('popup HTTP ' + r.status);
          return await r.text();
        }
        """,
        {"path": POPUP_PATH, "id": coin_id, "formId": form_id},
    )


# ============================================================================
# Orchestration
# ============================================================================


def resolve_catalog_url(args: argparse.Namespace) -> str:
    if args.investment_only:
        return INVESTMENT_CATALOG_URL
    return CATALOG_URL


async def scrape(args: argparse.Namespace) -> list[LantaCoin]:
    coins: list[LantaCoin] = []
    seen_keys: set[str] = set()
    catalog_url = resolve_catalog_url(args)

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
            for attempt in range(1, args.retries + 1):
                try:
                    log.info("Открываю %s (попытка %s/%s)", catalog_url, attempt, args.retries)
                    await page.goto(
                        catalog_url,
                        wait_until="domcontentloaded",
                        timeout=args.timeout,
                    )
                    # networkidle на lanta.ru часто не наступает и ломает контекст страницы.
                    await page.wait_for_timeout(3_000)
                    break
                except PlaywrightError as e:
                    log.warning("Навигация: попытка %s/%s — %s", attempt, args.retries, e)
                    if attempt < args.retries:
                        await asyncio.sleep(2 ** attempt)
            else:
                log.error("Не удалось открыть страницу каталога")
                return []

            if await page_is_captcha(page):
                if args.wait_captcha_secs > 0:
                    if args.headful:
                        log.warning(
                            "CAPTCHA — пройдите проверку в окне браузера (до %s с)...",
                            args.wait_captcha_secs,
                        )
                        if not await wait_captcha_cleared(page, args.wait_captcha_secs):
                            raise CaptchaBlockedError(
                                "CAPTCHA не пройдена за отведённое время"
                            )
                    else:
                        log.warning(
                            "--wait-captcha-secs игнорируется без --headful"
                        )
                if await page_is_captcha(page):
                    raise CaptchaBlockedError(
                        "lanta.ru показал CAPTCHA («Вы точно не робот?») вместо каталога. "
                        "Автоматический headless-доступ блокируется. "
                        "Попробуйте: python3 scrape_lanta_coins.py --headful "
                        "--wait-captcha-secs 120 --storage-state lanta_state.json "
                        "(после прохождения CAPTCHA сессия сохранится через --save-storage-state)."
                    )

            query = (args.query or "").strip()
            if query:
                await apply_catalog_search(page, query)

            await scroll_until_stable(page, args.scroll_passes, args.delay)
            list_items = await collect_list_items(page)
            log.info("Найдено карточек в каталоге: %s", len(list_items))

            for item in list_items:
                coin_id = item.get("id")
                form_id = item.get("formId") or "892"
                catalog_number: str | None = None
                if coin_id:
                    try:
                        popup_html = await fetch_popup_html(page, str(coin_id), str(form_id))
                        catalog_number = parse_article_from_popup_html(popup_html)
                    except PlaywrightError as e:
                        log.warning("Попап id=%s: %s", coin_id, e)
                    await asyncio.sleep(args.delay)

                coin = list_item_to_coin(item, catalog_number)
                if coin is None:
                    log.warning("Пропуск карточки: пустое название, id=%s", coin_id)
                    continue
                key = dedupe_key(item, coin)
                if key in seen_keys:
                    log.warning(
                        "Дубликат карточки (ключ %s): id=%s, «%s», артикул=%s, вес=%s г",
                        key,
                        coin_id,
                        coin.name,
                        coin.catalog_number,
                        coin.weight_g,
                    )
                    continue
                seen_keys.add(key)
                if coin.buy_price is None and coin.sell_price is None:
                    log.warning("Нет цен для '%s' (артикул %s)", coin.name, coin.catalog_number)
                coins.append(coin)
                if args.max_items is not None and len(coins) >= args.max_items:
                    break

            if args.save_storage_state:
                await context.storage_state(path=str(args.save_storage_state))
                log.info("Сессия сохранена: %s", args.save_storage_state)
        finally:
            await browser.close()

    return coins


# ============================================================================
# CLI
# ============================================================================


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Скрейпер каталога монет lanta.ru")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_MS, help="таймаут, мс")
    p.add_argument("--retries", type=int, default=DEFAULT_RETRIES, help="попыток навигации")
    p.add_argument("--delay", type=float, default=DEFAULT_DELAY, help="пауза между действиями, с")
    p.add_argument(
        "--scroll-passes",
        type=int,
        default=DEFAULT_SCROLL_PASSES,
        help="макс. число прокруток для lazy-load",
    )
    p.add_argument("--max-items", type=int, default=None, help="ограничить число монет")
    p.add_argument(
        "--query",
        default="",
        help="строка для поля «Найти» на странице каталога (пусто — без фильтра)",
    )
    p.add_argument(
        "--investment-only",
        action="store_true",
        help=f"только инвестиционные монеты (каталог: {INVESTMENT_CATALOG_URL})",
    )
    p.add_argument("--headful", action="store_true", help="показать окно браузера")
    p.add_argument(
        "--browser-channel",
        default="",
        choices=["", "chrome", "msedge", "chromium"],
        help="системный браузер (пусто — авто: chrome, msedge, bundled)",
    )
    p.add_argument(
        "--storage-state",
        type=Path,
        default=None,
        help="JSON с cookies/localStorage после ручного прохождения CAPTCHA",
    )
    p.add_argument(
        "--save-storage-state",
        type=Path,
        default=None,
        help="сохранить сессию после успешного сбора",
    )
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
    log.info("  Скрейпер каталога монет lanta.ru")
    log.info("=" * 60)
    started_at = datetime.now()

    scrape_status = "ok"
    error_message: str | None = None
    try:
        coins = await scrape(args)
    except CaptchaBlockedError as e:
        coins = []
        scrape_status = "captcha_blocked"
        error_message = str(e)
        log.error("%s", e)
    except PlaywrightError as e:
        coins = []
        scrape_status = "error"
        error_message = str(e)
        log.error("Ошибка Playwright: %s", e)

    result = {
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
