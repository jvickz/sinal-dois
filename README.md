# Sinal Dois · Loja de eletrônicos

Dois clientes encontram o último iPhone e finalizam a compra ao mesmo tempo. A Sinal Dois reserva a unidade para um deles e informa o saldo ao outro. O projeto reúne uma loja em React e TypeScript e uma plataforma de pedidos em Java e PostgreSQL, do catálogo à expedição.

![Loja Sinal Dois com o iPhone em destaque e o início do catálogo de eletrônicos](docs/loja.webp)

## Executar a demonstração

Com Docker e Docker Compose em execução, rode na pasta do projeto.

```sh
docker compose up --build -d
```

[Abrir a loja](http://127.0.0.1:8090/) · [Painel de operações](http://127.0.0.1:8090/?area=operacoes) · [Swagger UI](http://127.0.0.1:8090/swagger-ui.html)

Para entrar no painel, use `operador` e `demo-operacoes`. O catálogo apresenta modelos reais com imagens ilustrativas, preços fictícios em reais e pagamento simulado. A interface e a API são servidas pela mesma origem na máquina local. O banco roda na rede do Compose, com sessões e dados persistidos em volume.

Em um volume novo, o perfil `demo` inicializa `IPHONE-15-128` com uma unidade e `PS5-SLIM` com dez. A porta do host usa `RESERVA_PORT`, a senha local do painel usa `COMMERCE_OPERATOR_PASSWORD` e o prazo padrão de dois minutos pode ser alterado com `RESERVATION_TTL`.

## Vitrine e identidade visual

A vitrine alterna entre iPhone 15, PlayStation 5 Slim e MacBook Air M3. Os produtos ocupam a direita da capa sobre grafite, com detalhes em laranja e tipografia branca. O catálogo usa fundo claro para comparar os seis modelos, incluindo o ThinkPad T14, o Nintendo Switch OLED e o Mac mini M4. Cada ficha apresenta marca, preço, especificações e disponibilidade.

Na abertura de cada destaque, a foto aproxima-se do enquadramento final enquanto o título sobe. A luz acompanha o ponteiro e os botões permitem trocar de produto. Essa composição adapta o movimento do ZEN. A entrada dos cartões quando aparecem na tela veio do Northstar. Os dois protótipos são de Liana Pepanyan. A loja aceita setas do teclado e gestos de toque e respeita a preferência de movimento reduzido. As imagens foram geradas para este projeto. [ZEN](https://zen-customizable-webgl-shader-agency-template-v2.21st.app) · [Northstar](https://northstar-template-demo.vercel.app/) · [Galeria de agências do 21st.dev](https://21st.dev/community/templates/s/agency)

## Roteiro da loja até a expedição

1. Alterne os destaques com as setas ou deslize o dedo no celular. Filtre por **Videogames**, abra a ficha do PlayStation 5 Slim e volte a **Todos**.
2. Abra a loja também em uma janela privativa. Em ambas, coloque o iPhone 15 com uma unidade disponível no carrinho.
3. Finalize os dois checkouts. Um cria o pedido e reserva a unidade. O outro mostra o item indisponível.
4. No pedido criado, simule a aprovação do pagamento e reenvie o mesmo evento. A aprovação aparece uma vez no histórico.
5. Entre no painel e avance o pedido pago com **Separar pedido** e depois **Expedir pedido**. Veja a linha do tempo atualizar na loja.
6. Faça outro pedido com um PlayStation 5 Slim. O saldo cai de dez para nove. Simule a recusa ou deixe o prazo vencer e confira o retorno a dez unidades.

Para repetir com dados iniciais, use outro nome de projeto Compose e uma porta disponível.

```powershell
$env:RESERVA_PORT = '8091'
docker compose -p sinal-dois-demo-2 up --build -d
```

Abra `http://127.0.0.1:8091/`. `docker compose -p sinal-dois-demo-2 down` encerra essa instância e preserva seus dados no volume.

## API de reserva individual

Use uma demonstração com saldo inicial para observar também o conflito pela última unidade.

1. Em `GET /api/v1/inventory/{sku}`, consulte `IPHONE-15-128` e confira o saldo inicial.
2. Em `POST /api/v1/reservations`, use uma chave nova, como `checkout-001`, no cabeçalho `Idempotency-Key`. Envie `{"sku":"IPHONE-15-128","quantity":1}`. A resposta `201` informa o ID, o estado `HELD` e o prazo em UTC.
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

Defina `COMMERCE_BASE_URL` se a demonstração estiver em outra porta. O Playwright usa dois compradores em sessões independentes e um operador. A suíte verifica teclado no desktop, gesto de toque e movimento reduzido no celular, ficha técnica, disputa da última unidade e devolução do saldo após recusa. Para salvar as capturas de desktop, celular, acompanhamento e operações, defina `COMMERCE_SCREENSHOTS` com a pasta de destino. O workflow do GitHub Actions executa a suíte Java e os testes da loja no navegador.

### Linha para o currículo

> Desenvolvi a Sinal Dois, loja de eletrônicos em React, TypeScript, Spring Boot e PostgreSQL. Implementei reserva transacional de estoque, processamento idempotente de pagamentos simulados e painel de expedição. Automatizei no Playwright o cenário de dois compradores disputando a última unidade.
