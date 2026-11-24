"""Exercise a fresh local Compose demo with three isolated browser sessions."""

import asyncio
import json
import os
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import urlopen

from playwright.async_api import async_playwright, expect


BASE_URL = os.environ.get("COMMERCE_BASE_URL", "http://127.0.0.1:8090").rstrip("/")
ORDER_URL = f"{BASE_URL}/api/v1/orders"
SCREENSHOTS = os.environ.get("COMMERCE_SCREENSHOTS")


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


async def screenshot(page, filename):
    if SCREENSHOTS:
        folder = Path(SCREENSHOTS)
        folder.mkdir(parents=True, exist_ok=True)
        await page.screenshot(path=folder / filename, full_page=True)


async def verify_commerce():
    wait_for_api()
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(channel=os.environ.get("COMMERCE_BROWSER"))
        async with await browser.new_context(viewport={"width": 1440, "height": 1000}) as first:
            async with await browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True) as second:
                async with await browser.new_context(viewport={"width": 1440, "height": 1000}) as operations:
                    pages = [await first.new_page(), await second.new_page()]
                    await pages[0].goto(BASE_URL)
                    await expect(pages[0].get_by_test_id("add-CAFE-250G")).to_be_visible()
                    await screenshot(pages[0], "loja.png")
                    await asyncio.gather(*(add_to_cart(page, "CAFE-250G") for page in pages))
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

                    await other.get_by_role("button", name="Remover Café Serra Clara", exact=True).click()
                    await add_to_cart(other, "FILTRO-01")
                    response = await submit_cart(other)
                    assert response.status == 201
                    await wait_for_status(other, "AWAITING_PAYMENT")
                    await submit_payment_event(other, "Simular recusa")
                    await wait_for_status(other, "PAYMENT_FAILED")
                    catalog = await (await other.request.get(f"{BASE_URL}/api/v1/catalog")).json()
                    assert next(item for item in catalog if item["sku"] == "FILTRO-01")["available"] == 10

        await browser.close()
    print("Playwright: disputa, reenvio, expedição, recarga e recusa verificadas.")


if __name__ == "__main__":
    asyncio.run(verify_commerce())
