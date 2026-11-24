"""Exercise a fresh local Compose demo with three isolated browser sessions."""

import asyncio
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import urlopen

from playwright.async_api import async_playwright, expect


BASE_URL = os.environ.get("COMMERCE_BASE_URL", "http://127.0.0.1:8090").rstrip("/")
ORDER_URL = f"{BASE_URL}/api/v1/orders"
SCREENSHOTS = os.environ.get("COMMERCE_SCREENSHOTS")
IPHONE = "IPHONE-15-128"
PLAYSTATION = "PS5-SLIM"
MACBOOK = "MACBOOK-AIR-M3"


def wait_for_api():
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        try:
            with urlopen(f"{BASE_URL}/actuator/health", timeout=2) as response:
                if json.load(response).get("status") == "UP":
                    return
        except OSError:
            time.sleep(1)
    raise RuntimeError("A API de demonstração não ficou disponível.")


async def add_to_cart(page, sku):
    await page.goto(BASE_URL)
    await page.get_by_test_id(f"add-{sku}").click()
    await page.get_by_role("link", name="Carrinho", exact=True).click()
    await expect(page.get_by_role("button", name="Finalizar pedido")).to_be_enabled()


async def submit_cart(page):
    async with page.expect_response(
        lambda response: response.url == ORDER_URL and response.request.method == "POST"
    ) as result:
        await page.get_by_role("button", name="Finalizar pedido").click()
    return await result.value


async def wait_for_status(page, status):
    await expect(page.get_by_test_id("order-status")).to_have_attribute(
        "data-status", status, timeout=12_000
    )


async def submit_payment_event(page, label):
    async with page.expect_response(
        lambda response: response.url.endswith("/payment-events") and response.request.method == "POST"
    ) as result:
        await page.get_by_role("button", name=label).click()
    response = await result.value
    assert response.status == 202, await response.text()
    return response.request.post_data_json["eventId"]


async def screenshot(page, filename, full_page=True):
    if SCREENSHOTS:
        folder = Path(SCREENSHOTS)
        folder.mkdir(parents=True, exist_ok=True)
        await page.screenshot(path=folder / filename, full_page=full_page, animations="disabled")


async def verify_storefront(page, catalog, mobile=False):
    await page.goto(BASE_URL)
    await expect(page).to_have_title(re.compile("Sinal Dois", re.IGNORECASE))
    hero = page.get_by_test_id("showcase")
    heading = hero.get_by_role("heading", level=1)
    await expect(heading).to_have_text(catalog[IPHONE]["name"])
    await page.evaluate("document.fonts.ready")
    next_slide = hero.get_by_role("button", name="Próximo destaque")
    initial_y = (await next_slide.bounding_box())["y"]
    await next_slide.click()
    await expect(heading).to_have_text(catalog[PLAYSTATION]["name"])
    if not mobile:
        assert abs((await next_slide.bounding_box())["y"] - initial_y) < 2
    if mobile:
        assert await page.evaluate("matchMedia('(prefers-reduced-motion: reduce)').matches")
        assert await page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        assert await hero.evaluate("""element => [...element.querySelectorAll('*')].every(node => {
            const style = getComputedStyle(node);
            return style.animationName === 'none' || style.animationDuration.split(',')
                .every(duration => {
                    const value = duration.trim();
                    const milliseconds = parseFloat(value) * (value.endsWith('ms') ? 1 : 1000);
                    return milliseconds < 16;
                });
        })""")
        box = await hero.bounding_box()
        # Dispara um gesto real no Chromium móvel com dois pontos intermediários.
        cdp = await page.context.new_cdp_session(page)
        try:
            y = box["y"] + box["height"] * 0.4
            for index, ratio in enumerate((0.80, 0.62, 0.42, 0.22)):
                await cdp.send("Input.dispatchTouchEvent", {
                    "type": "touchStart" if index == 0 else "touchMove",
                    "touchPoints": [{"x": box["x"] + box["width"] * ratio, "y": y}],
                })
            await cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
        finally:
            await cdp.detach()
    else:
        await hero.get_by_role("button", name="Ver produto").focus()
        await page.keyboard.press("ArrowRight")
        await expect(hero).to_be_focused()
    await expect(heading).to_have_text(catalog[MACBOOK]["name"])
    await hero.get_by_role("button", name="Destaque anterior").click()
    await expect(heading).to_have_text(catalog[PLAYSTATION]["name"])
    await hero.get_by_role("button", name="Destaque anterior").click()
    await expect(heading).to_have_text(catalog[IPHONE]["name"])

    await page.get_by_role("button", name="Videogames", exact=True).click()
    cards = page.locator('[data-testid^="product-"]')
    await expect(cards).to_have_count(2)
    await expect(page.get_by_test_id("product-SWITCH-OLED")).to_be_visible()
    ps5 = catalog[PLAYSTATION]
    details = page.get_by_test_id("product-" + PLAYSTATION).get_by_role(
        "button", name="Ficha técnica de " + ps5["name"], exact=True
    )
    await details.click()
    dialog = page.get_by_role("dialog")
    await expect(dialog).to_contain_text(ps5["name"])
    await expect(dialog).to_contain_text(ps5["brand"])
    for spec in ps5["specs"]:
        await expect(dialog).to_contain_text(spec)
    if mobile:
        await dialog.get_by_role("button", name="Fechar ficha técnica").click()
    else:
        await page.keyboard.press("Escape")
    await expect(dialog).to_be_hidden()
    await expect(details).to_be_focused()
    if not mobile:
        await details.click()
        await dialog.get_by_role("button", name="Adicionar ao carrinho").click()
        await dialog.get_by_role("link", name=re.compile("Ver carrinho")).click()
        await expect(page.get_by_role("main")).to_be_focused()
        await expect(page.get_by_role("heading", name=ps5["name"])).to_be_visible()
        await page.get_by_role("button", name="Remover " + ps5["name"]).click()
        await page.get_by_role("link", name="Explorar a loja").click()
    await page.get_by_role("button", name="Todos", exact=True).click()
    await expect(cards).to_have_count(len(catalog))
    if SCREENSHOTS:
        for card in await cards.all():
            await card.scroll_into_view_if_needed()
            await card.locator("img").evaluate("(img) => img.decode()")
        await page.evaluate("document.fonts.ready")
        await page.evaluate("window.scrollTo({top: 0, behavior: 'instant'})")
        await screenshot(page, "loja-mobile.png" if mobile else "loja.png")
        if not mobile:
            await screenshot(page, "capa.png", full_page=False)


async def verify_commerce():
    wait_for_api()
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(channel=os.environ.get("COMMERCE_BROWSER"))
        async with await browser.new_context(viewport={"width": 1440, "height": 1000}) as first:
            async with await browser.new_context(
                viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, reduced_motion="reduce"
            ) as second:
                async with await browser.new_context(viewport={"width": 1440, "height": 1000}) as operations:
                    pages = [await first.new_page(), await second.new_page()]
                    initial_catalog = await (await first.request.get(f"{BASE_URL}/api/v1/catalog")).json()
                    catalog = {item["sku"]: item for item in initial_catalog}
                    assert set(catalog) == {IPHONE, PLAYSTATION, MACBOOK, "THINKPAD-T14", "SWITCH-OLED", "MAC-MINI-M4"}
                    assert catalog[IPHONE]["available"] == 1 and catalog[PLAYSTATION]["available"] == 10
                    await asyncio.gather(verify_storefront(pages[0], catalog), verify_storefront(pages[1], catalog, mobile=True))
                    await asyncio.gather(*(add_to_cart(page, IPHONE) for page in pages))
                    responses = await asyncio.gather(*(submit_cart(page) for page in pages))
                    assert sorted(response.status for response in responses) == [201, 409]
                    winner_index = next(index for index, response in enumerate(responses) if response.status == 201)
                    winner, other = pages[winner_index], pages[1 - winner_index]
                    order_id = (await responses[winner_index].json())["id"]
                    refused = await responses[1 - winner_index].json()
                    assert refused["code"] == "INSUFFICIENT_STOCK"
                    await expect(other.get_by_role("alert")).to_contain_text(refused["detail"])
                    await wait_for_status(winner, "AWAITING_PAYMENT")
                    assert parse_qs(urlparse(winner.url).query)["pedido"] == [order_id]

                    event_id = await submit_payment_event(winner, "Simular aprovação")
                    await wait_for_status(winner, "PAID")
                    assert await submit_payment_event(winner, "Reenviar mesmo evento") == event_id
                    await wait_for_status(winner, "PAID")

                    panel = await operations.new_page()
                    await panel.goto(f"{BASE_URL}/?area=operacoes")
                    await panel.get_by_label("Usuário").fill("operador")
                    await panel.get_by_label("Senha").fill(
                        os.environ.get("COMMERCE_OPERATOR_PASSWORD", "demo-operacoes")
                    )
                    await panel.get_by_role("button", name="Entrar", exact=True).click()
                    row = panel.get_by_test_id(f"order-{order_id}")
                    await row.get_by_role("button", name="Separar pedido").click()
                    await wait_for_status(winner, "PICKING")
                    await row.get_by_role("button", name="Expedir pedido").click()
                    await wait_for_status(winner, "SHIPPED")
                    await winner.reload()
                    await wait_for_status(winner, "SHIPPED")
                    await expect(winner.get_by_test_id("timeline")).to_be_visible()
                    await screenshot(panel, "operacoes.png")
                    await screenshot(winner, "acompanhamento.png")

                    await other.get_by_role("button", name="Remover " + catalog[IPHONE]["name"], exact=True).click()
                    await add_to_cart(other, PLAYSTATION)
                    response = await submit_cart(other)
                    assert response.status == 201
                    await wait_for_status(other, "AWAITING_PAYMENT")
                    held = await (await other.request.get(f"{BASE_URL}/api/v1/catalog")).json()
                    assert next(item for item in held if item["sku"] == PLAYSTATION)["available"] == 9
                    await submit_payment_event(other, "Simular recusa")
                    await wait_for_status(other, "PAYMENT_FAILED")
                    restored = await (await other.request.get(f"{BASE_URL}/api/v1/catalog")).json()
                    assert next(item for item in restored if item["sku"] == PLAYSTATION)["available"] == 10

        await browser.close()
    print("Playwright verificou a vitrine no desktop e no celular, a disputa de estoque, o reenvio, a expedição e a reposição após recusa.")


if __name__ == "__main__":
    asyncio.run(verify_commerce())
