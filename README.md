# Casa Torra · Plataforma de pedidos e estoque

Loja de demonstração com checkout, acompanhamento de pedidos e painel de operações. A mesma base PostgreSQL atende o checkout e a API de reserva individual. A última unidade de um produto só pode pertencer a uma compra por vez.

## Executar a demonstração

Com Docker e Docker Compose em execução, rode na pasta do projeto.

```sh
docker compose up --build -d
```

[Abrir a loja](http://127.0.0.1:8090/) · [Painel de operações](http://127.0.0.1:8090/?area=operacoes) · [Swagger UI](http://127.0.0.1:8090/swagger-ui.html)

Para entrar no painel, use `operador` e `demo-operacoes`. O pagamento é simulado e o catálogo usa produtos fictícios. A interface e a API são servidas pela mesma origem na máquina local. O banco roda na rede do Compose, com sessões e dados persistidos em volume.

O perfil `demo` inicializa `CAFE-250G` com uma unidade e `FILTRO-01` com dez. A porta do host usa `RESERVA_PORT`, a senha local do painel usa `COMMERCE_OPERATOR_PASSWORD` e o prazo padrão de dois minutos pode ser alterado com `RESERVATION_TTL`.

## Roteiro da loja até a expedição

1. Abra a loja em uma janela comum e outra privativa. Em ambas, coloque o café com uma unidade disponível no carrinho.
2. Finalize os dois checkouts. Um cria o pedido e reserva a unidade. O outro mostra o item indisponível.
3. No pedido criado, simule a aprovação do pagamento e reenvie o mesmo evento. A aprovação aparece uma vez no histórico.
4. Entre no painel e avance o pedido pago com **Separar pedido** e depois **Expedir pedido**. Veja a linha do tempo atualizar na loja.
5. Faça outro pedido com filtros. Simule a recusa ou deixe o prazo vencer. Consulte o catálogo para conferir as unidades devolvidas.

Para repetir com dados iniciais, use outro nome de projeto Compose e uma porta disponível.

```powershell
$env:RESERVA_PORT = '8091'
docker compose -p casa-torra-demo-2 up --build -d
```

Abra `http://127.0.0.1:8091/`. `docker compose -p casa-torra-demo-2 down` encerra essa instância e preserva seus dados no volume.

## API de reserva individual

1. Em `GET /api/v1/inventory/{sku}`, consulte `CAFE-250G` e confira o saldo inicial.
2. Em `POST /api/v1/reservations`, use uma chave nova, como `checkout-001`, no cabeçalho `Idempotency-Key`. Envie `{"sku":"CAFE-250G","quantity":1}`. A resposta `201` informa o ID, o estado `HELD` e o prazo em UTC.
3. Repita a chamada com a mesma chave e corpo. A resposta `200` contém o mesmo ID. Troque apenas a chave e envie novamente. A resposta `409` apresenta `INSUFFICIENT_STOCK`.
4. Cancele a reserva pelo ID em `POST /api/v1/reservations/{id}/cancel` e consulte o estoque. A unidade volta uma vez, mesmo que você repita o cancelamento. Crie outra reserva com uma chave nova e deixe o prazo vencer para observar `EXPIRED`.
5. Abra [ReservationApiIT](src/test/java/com/jvmarinho/reserva/ReservationApiIT.java) para mostrar como o teste dispara as duas compras simultaneamente e verifica que só uma reserva foi gravada.

O fluxo de confirmação muda a reserva para `CONFIRMED` e mantém o débito. As reservas ficam registradas no banco entre reinícios. `docker compose down` encerra a demonstração preservando o volume.

## Decisões técnicas

| Decisão | Comportamento observável |
| --- | --- |
| Débito condicional no PostgreSQL | A atualização só é aplicada se houver unidades suficientes. A restrição do banco protege o saldo contra valores negativos. |
| Chave de idempotência única | Retentativas simultâneas recuperam a mesma reserva. O mesmo identificador com outro SKU ou quantidade retorna `409`. Uma tentativa recusada pode ser repetida depois de repor o saldo. |
| Bloqueio da linha da reserva | Confirmação, cancelamento e expiração verificam o estado e o prazo após adquirir o bloqueio. A devolução do saldo ocorre na mesma transação da mudança de estado. |
| Worker em lotes | O worker consulta reservas vencidas persistidas e retoma a expiração após reiniciar. A consulta HTTP mostra o estado armazenado e o prazo em UTC. |
| Checkout com vários itens | O servidor registra preço e quantidade no pedido. A transação reserva todos os itens ou reverte todos os débitos. Os SKUs são processados em ordem consistente. |
| Pagamento assíncrono | O simulador grava eventos em uma fila PostgreSQL. O worker bloqueia o evento e o pedido, verifica o prazo e grava o resultado e o saldo na mesma transação. |
| Sessões e operação | As sessões persistem no PostgreSQL. Cada comprador acompanha seus próprios pedidos, e separação e expedição exigem acesso de operador. |

O [documento OpenAPI](http://127.0.0.1:8090/v3/api-docs) descreve o contrato HTTP. As falhas de validação e de regra de negócio usam `application/problem+json` e um campo `code` para distinguir as causas. Para observar a aplicação, consulte [saúde](http://127.0.0.1:8090/actuator/health) e [contadores de reservas](http://127.0.0.1:8090/actuator/metrics/inventory.reservations.events) no Actuator. Os contadores representam o processo atual.

## Testar e reproduzir

Com Java 21, Maven e Docker em execução, rode:

```sh
mvn -B -ntp verify
```

O Surefire verifica as regras de prazo com um relógio controlado. O Failsafe executa testes HTTP contra PostgreSQL 18 isolado por Testcontainers. Para verificar o frontend com Node.js 24, rode `npm ci` e `npm run build` em `web/`.

Os testes de navegador usam dados fictícios recém-carregados. Depois de subir o Compose, rode:

```sh
python -m pip install -r tests/browser/requirements.txt
python -m playwright install chromium
python tests/browser/test_commerce.py
```

Defina `COMMERCE_BASE_URL` se a demonstração estiver em outra porta. O workflow do GitHub Actions executa a suíte Java e os testes da loja no navegador.

### Linha para o currículo

> Desenvolvi uma plataforma full-stack de pedidos com React, TypeScript, Spring Boot e PostgreSQL. Implementei reserva transacional de carrinho, processamento idempotente de pagamentos simulados e painel de expedição, com testes automatizados de concorrência e do fluxo no navegador.
