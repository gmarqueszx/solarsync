# Planilhas-modelo de importação

Sete planilhas para preencher com os dados da `PLANILHA_TESTE_-_PROJETOS_.xlsx` antes de rodar o
script de importação (passo 9 da seção 13 do `CLAUDE.md`). Cada arquivo tem três abas:

- **Dados** — o que você preenche. Cabeçalho verde com `*` marca coluna obrigatória; as linhas
  amarelas são exemplo e devem ser **apagadas antes de importar**. Passe o mouse no cabeçalho
  para ver a explicação da coluna.
- **Instruções** — a mesma explicação em tabela, mais o que checar antes de importar.
- **Listas** — os valores que o sistema aceita. Não editar: é a fonte das listas suspensas.

| Arquivo | Etapa | Uma linha é… |
|---|---|---|
| `00_usuarios.xlsx` | pré-requisito | uma pessoa que opera o sistema |
| `01_clientes.xlsx` | 1 — entrada | um cliente |
| `02_pendencias.xlsx` | 1 — pendência na Coelba | uma pendência |
| `03_debitos.xlsx` | 2 — agência virtual | **uma consulta de débito**, não um cliente |
| `04_projetos.xlsx` | 2 e 3 — homologação | um projeto |
| `05_vistorias.xlsx` | 4 — vistoria | uma vistoria (com suas idas e voltas) |
| `06_unificacoes.xlsx` | 4 — unificação | uma unificação e seu desligamento |

## Como as planilhas se ligam

Os ids do banco ainda não existem na hora de preencher, então as planilhas se referenciam por
códigos que **você inventa**:

- `chave_cliente` (planilha 01) é usada por 02, 03, 04 e 06.
- `chave_projeto` (planilha 04) é usada por 05.
- Pessoas são referenciadas pelo **e-mail** cadastrado na planilha 00.

Basta serem únicos e consistentes entre os arquivos — `C001`, `C002`, `P001` servem. Eles não
vão para o banco.

## Ordem de importação

A ordem não é arbitrária: o sistema cria projeto sozinho em dois momentos, e algumas ações são
recusadas se um passo anterior faltar.

1. **00 usuários** — todo o resto aponta para eles.
2. **01 clientes** — só o cadastro, ainda sem mexer na triagem.
3. **02 pendências** — apenas *criar* (nascem `ABERTA`). Isso já marca o cliente como
   `COM_PENDENCIA` automaticamente.
4. **03 débitos** — todas as consultas, em ordem cronológica.
5. **04 projetos** — *criar* (nascem `RECEBIDO`), ainda sem avançar status.
6. **02 pendências** — agora sim `/resolver` e `/cancelar`.
7. **01 clientes** — agora sim `/sem-pendencia` para quem tem `status_triagem = SEM_PENDENCIA`.
8. **04 projetos** — avançar cada um até o `status_final`.
9. **05 vistorias**.
10. **06 unificações**.

**Por que 04 vem antes de 02-resolver e 01-triagem**: resolver a pendência e marcar o cliente
como sem pendência disparam a criação automática do projeto. O sistema reaproveita um projeto
que já exista em `RECEBIDO` ou `AGUARDANDO_ENVIO` — mas se nenhum existir, ele cria um vazio
(tipo `PROJETO_INICIAL`, data de hoje, sem analista) e a linha da planilha 04 viraria um
**segundo** projeto do mesmo cliente.

**Por que 03 vem antes de 02-resolver**: resolver pendência exige consulta de débito do tipo
`PENDENCIA` registrada, e encaminhar projeto exige a do tipo `HOMOLOGACAO` — sem elas o sistema
recusa com `409 DEBITO_NAO_CONSULTADO`. Vale inclusive para cliente que nunca deveu: ele precisa
de uma linha `QUITADO` de cada tipo.

## Duas coisas que o script de importação vai precisar resolver

1. **`resolvido_em` da pendência não é retroativo.** `POST /api/pendencias/{id}/resolver` grava
   `Instant.now()` — não há campo de data no corpo. Importar por ali zera o "tempo médio de
   resolução de pendência" do dashboard. Saídas: o script grava a coluna direto no banco depois
   da chamada, ou o endpoint ganha um campo opcional de data (mudança pequena em
   `PendenciaService.atualizarStatus`). As demais datas de negócio têm caminho retroativo:
   `solicitadoEm` na criação da pendência, `consultadoEm` no débito, `dataEncaminhado` e
   `dataAprovacao` no projeto, e as datas das ações de vistoria e desligamento.

2. **O importador precisa rodar com a conta de ADMINISTRADOR.** Registros que chegam fora de
   ordem (a planilha antiga tem vários) só entram por
   `POST /api/projetos/{id}/corrigir-status`, restrito a admin e com justificativa obrigatória.

Os timestamps do `historico_status` serão os do momento da importação — é o carimbo de quando o
sistema soube. As métricas do dashboard saem das datas de negócio das colunas acima, e é por
isso que deixá-las em branco custa caro.

## Conferências rápidas antes de mandar importar

- Toda `chave_cliente` usada em 02/03/04/06 existe na planilha 01.
- Todo `chave_projeto` usado em 05 existe na planilha 04, e esse projeto tem `data_instalacao`.
- Todo e-mail usado existe na planilha 00.
- Cliente que ficou parado devendo tem **duas** linhas na 03 (a `ATIVO` e a `QUITADO`).
- Projeto com `status_final` `ENCAMINHADO`/`APROVADO`/`REPROVADO`/`REENCAMINHADO` tem
  `data_encaminhado`; `APROVADO` tem `data_aprovacao`; quem passou por reprova tem
  `motivo_reprova`.
- Unificação com `desligamento_status` diferente de `NAO_SOLICITADO` tem `feita = SIM` e
  `desligamento_solicitado_em` preenchida.
- As linhas amarelas de exemplo foram apagadas.

⚠️ `00_usuarios.xlsx` preenchido carrega senhas iniciais — não versionar o arquivo preenchido.
