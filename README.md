# Implementa-o-do-Cen-rio-do-Caso-de-Uso---Java

# Minha Refeição — Caso de Uso "Assinar Plano de Refeições"

Implementação em Java (console) do cenário modelado no Diagrama de Sequência e no Diagrama de Classes de Projeto (ver Wiki do repositório). O código segue a arquitetura **Fronteira → Controle → Entidades (Domínio)**, com persistência em **arquivos CSV**.

Todo o código está no arquivo `MinhaRefeicao.java`, organizado em blocos comentados: entidades, coleções e persistência, serviços, controle e fronteira. Os nomes das classes são os mesmos do diagrama.

## Requisitos
- JDK 17 ou superior (testado com JDK 21)
- Nenhuma biblioteca externa

## Como executar

Na pasta onde está o arquivo `MinhaRefeicao.java`:

```bash
java MinhaRefeicao.java
```

Esse comando compila e executa de uma vez. Se preferir compilar separado:

```bash
javac -encoding UTF-8 MinhaRefeicao.java
java MinhaRefeicao
```

Digite `sair` em qualquer pergunta para cancelar.

> No Windows, se os acentos aparecerem errados no terminal, rode antes `chcp 65001`.

## Como exercitar os fluxos

| Fluxo | Como provocar |
|---|---|
| **Principal** (passos 1–32) | Celular com 10 ou 11 dígitos → código SMS → plano → preferências → pratos → endereço → cartão válido |
| **FA1** – código inválido | Digite um código errado. Após 3 erros o sistema bloqueia e reenvia um novo código |
| **FA2** – limite do plano excedido | Selecione mais pratos numa categoria do que o plano permite (Essencial = 3, Completo = 5, Premium = 7) |
| **FA3** – pagamento não autorizado | Use cartão terminado em `0000` (limite insuficiente), validade vencida ou número com menos de 16 dígitos. Depois escolha tentar outro cartão (`s`) ou encerrar (`n`) |

Dados úteis para teste:

- **SMS simulado:** o código aparece no console (`[SMS simulado] ...`). Para fixar o código em demonstrações: `SMS_CODIGO_FIXO=123456 java MinhaRefeicao.java` (Windows: `set SMS_CODIGO_FIXO=123456` antes do comando).
- **Cartão válido:** `4111111111111111`, validade futura (ex.: `12/2030`), CVV com 3 dígitos.
- **Preferências aceitas:** `VEGETARIANO`, `SEM_LACTOSE`, `SEM_GLUTEN` (separadas por vírgula; Enter = nenhuma).

## Persistência

Os arquivos CSV são criados automaticamente na pasta `dados/`, na primeira execução:

| Arquivo | Conteúdo |
|---|---|
| `dados/assinantes.csv` | Assinantes e cartões associados (**somente titular + 4 últimos dígitos**) |
| `dados/assinaturas.csv` | Assinaturas com status, endereço, itens, pagamentos e protocolo |

Planos e cardápio estão definidos no código (classes `CatalogoPlanos` e `Cardapio`). O número completo e o CVV do cartão **nunca** são gravados.

## Correspondência com o diagrama

| Papel no projeto (UML) | Classes no código |
|---|---|
| Fronteira | `TelaAssinatura` |
| Controle | `AssinaturaController` |
| Entidades (domínio) | `Assinante`, `Assinatura`, `Plano`, `PreferenciaAlimentar`, `Prato`, `Pedido`, `ItemPedido`, `Endereco`, `Pagamento`, `CartaoDeCredito` |
| Coleções / persistência | `Assinantes`, `CatalogoPlanos`, `Cardapio`, `RepositorioAssinaturas` |
| Serviços / atores secundários | `ServicoSMS`, `ProcessadorPagamento`, `OperadoraCartaoProxy`, `GeradorProtocolo` |

Cada método público do `AssinaturaController` corresponde a uma mensagem recebida da tela no diagrama de sequência. O mapeamento completo entre mensagens, métodos e fluxos alternativos está na Wiki do repositório.

## Estrutura do repositório

```
MinhaRefeicao.java     # código-fonte completo
README.md
dados/                 # CSVs gerados na execução
```
