# SolarSync — CLAUDE.md

Documento de referência do projeto para orientar o desenvolvimento assistido por IA.
Manter atualizado a cada decisão relevante — este arquivo é a fonte de verdade do escopo.

## 1. Contexto

A ConectSol controla hoje todo o processo de homologação de projetos solares (Coelba/Neoenergia)
por uma planilha Excel com 18 abas, uma por responsável ou subtipo de projeto. O objetivo do
SolarSync é substituir essa planilha por um sistema web com:

- API REST em Java/Spring Boot
- Banco PostgreSQL
- Frontend consumindo a API (design system: estilo Navan, paleta verde — ver seção 7)
- Autenticação e autorização por hierarquia (RBAC)
- Dashboard gerencial com métricas de tempo de ciclo, aberto a todos os papéis (ver seção 5)

O fluxo de negócio mapeado (a partir do processo real da ConectSol) tem 4 macro-etapas:

1. **Entrada e pendências** — cliente validado pelo financeiro entra no fluxo; verifica-se
   pendência na Coelba (troca de titularidade, ligação nova, extensão de rede, etc.). Sem
   pendência, segue direto. Com pendência, resolve-se na Coelba e atualiza-se manualmente em
   dois lugares (planilha + Trello) — **ponto de retrabalho identificado**.
2. **Débito e homologação** — verifica-se débito pendente do cliente (agência virtual Coelba).
   Com débito, cliente é cobrado até quitar. Sem débito, o projeto é preenchido e enviado à
   Coelba com ART.
3. **Acompanhamento** — status acompanhado por e-mail diário (aprovado / reprovado / em
   análise). Reprovado volta para correção e reenvio. Aprovado segue para vistoria.
4. **Vistoria e unificação** — vistoria solicitada pós-instalação; se aprovada, segue para
   pós-venda; se há unificação pendente, valida-se e desliga-se o medidor ou abre-se O.S.

## 2. Análise da planilha atual (`PLANILHA_TESTE_-_PROJETOS_.xlsx`)

18 abas, agrupáveis em 6 papéis funcionais:

| Abas de origem | Papel no processo | Observação |
|---|---|---|
| `PARAFAZER`, `PENDENCIASNYCOLLE`, `PENDENCIASOLICITACOES` | Fila de pendências (etapa 1) | Colunas: vendedor, data pagamento, cliente, pego, pendência, solicitado, status, conclusão |
| `IVAN`, `LARISSA`, `CAMILA` | Homologação de projeto (etapas 2–3) | Mesma estrutura em 3 abas duplicadas por analista: débito, data ART, data feito, data encaminhado, data reprova+motivo, data reencaminhado, data aprovação, dias para aprovação |
| `AUMENTO DE POTENCIA`, `AMPLIAÇOES`, `PROJETO COM UMA PLACA A MAIS`, `MUDANÇA DE INVERSOR 5KW`, `INVERSORES SEPARADOS` | Casos operacionais, não subtipos | Mesmo ciclo de vida — viram um campo `tipo_projeto`, e não cinco valores: o usuário confirmou que só há **três** tipos (`PROJETO_INICIAL`, `AMPLIACAO`, `CORRECAO`); estas abas caem em ampliação ou correção |
| `IGOR`, `UNIFICAÇÕES` | Unificação (etapa 4) | Cliente, cidade, projetista, info da unificação, feita?, desligamento |
| `DESLIGAMENTOS E ETC` | Desligamento de medidor (etapa 4) | Cliente, pego, status |
| `CLIENTES_DEBITOS` | Cache de status de débito | Cliente, status, última consulta |
| `CATS` | Protocolo/documento vinculado | Cliente, número, status |
| `Página12`, `Página20` | Lixo — confirmado, não migrar | — |

**Conclusão-chave**: a planilha é organizada por *responsável*, não por *etapa*. Isso duplica
trabalho (3 abas idênticas para Ivan/Larissa/Camila) e impede visão consolidada — exatamente o
que o dashboard do gestor precisa resolver.

## 3. Modelo de domínio proposto

Entidades centrais (nomes provisórios, ajustar durante desenvolvimento):

- **Cliente** — nome, cidade, vendedor, data_pagamento, **uc_coelba**, **telefone**. A UC é
  como a Coelba identifica o cliente, então é chave de busca (`GET /api/clientes?nome=` casa
  nome **ou** UC). Sem UNIQUE: um cliente pode ter mais de uma UC — é disso que trata a
  unificação; aqui fica a principal
  - **status_triagem** (`AGUARDANDO_VERIFICACAO` → `COM_PENDENCIA` | `SEM_PENDENCIA`): o
    resultado da checagem de pendência na Coelba (etapa 1). Existe porque **"este cliente não
    tem pendência" precisava ser um fato registrado**, e não a ausência de registro: antes,
    "a Nycole checou e não achou nada" e "ninguém olhou esse cliente ainda" eram
    indistinguíveis — e é o segundo que se perde de vista. `AGUARDANDO_VERIFICACAO` é a fila de
    trabalho da triagem (`GET /api/clientes?statusTriagem=AGUARDANDO_VERIFICACAO`)
  - `COM_PENDENCIA` é marcado **automaticamente** quando uma Pendencia é criada
    (`cliente/PendenciaCriadaTriagemListener`, síncrono na mesma transação): a pendência é a
    prova da checagem, e exigir marcação à mão reintroduziria o retrabalho de atualizar em dois
    lugares que é a dor da planilha + Trello
  - `SEM_PENDENCIA` vem de `POST /api/clientes/{id}/sem-pendencia` e faz o projeto nascer em
    `RECEBIDO`, o caminho "segue direto" da etapa 1. `POST /api/clientes/{id}/reverificar`
    devolve o cliente à fila (novo ciclo, ou marcação errada) sem apagar o projeto
  - O enum **não tem `podeIrPara`**, ao contrário dos outros: as três transições são todas
    legítimas na operação real (pendência aparece depois, ou era engano, ou o cliente volta num
    novo ciclo). Não sobra transição absurda para barrar, e máquina de estados que aceita tudo é
    burocracia sem proteção
  - O `PUT /api/clientes/{id}` **não** mexe em `status_triagem` — corrigir telefone não pode, de
    passagem, apagar o fato de que a Coelba já foi consultada
- **Pendencia** — cliente_id, tipo, status, solicitado_em, resolvido_em, responsavel_id, observação
- **Debito** — cliente_id, **tipo**, status (ativo/quitado), última_consulta_em, detectado_em,
  quitado_em, consultado_por_id. É o retrato da última consulta na agência virtual, não um
  lançamento contábil: reconsultar atualiza o mesmo registro, e o vai-e-vem fica em
  `historico_status`.
  <p>
  **São dois registros por cliente, um por `tipo`** (`uk_debito_cliente_tipo`), porque o débito
  da Coelba trava duas coisas diferentes e a operação precisa distingui-las:
  - `PENDENCIA` — impede a Coelba de resolver a pendência (troca de titularidade, ligação nova).
    Consultado por quem trabalha a pendência, na etapa 1
  - `HOMOLOGACAO` — impede o envio do projeto à Coelba. **Consultado pelo projetista**, ao
    receber o cliente com a pendência já concluída. Era isso que o sistema modelava errado:
    cobrava a consulta de quem trabalha a pendência, que na prática não a faz
  <p>
  ⚠️ Isto **reverteu** o registro único por cliente. Um registro só travava as duas etapas com o
  mesmo dado, então quitar para uma destravava a outra sem ninguém ter olhado, e não havia como
  responder "este cliente está parado por quê" — que é a pergunta do financeiro. Um cliente pode
  ter mais de uma UC, e a UC da pendência não é necessariamente a geradora.
  <p>
  `detectado_em` / `quitado_em` são o **relógio do tempo parado**, exposto como `diasParado` no
  response (nulo quando não está ATIVO — nulo ≠ zero, como nas médias do dashboard). A média do
  dashboard continua saindo do `historico_status`; as colunas existem porque a listagem precisa
  do tempo linha a linha, e varrer o histórico por linha seria caro. Reconsultar e achar ATIVO
  de novo **não** reinicia `detectado_em`; quitar e reabrir reinicia
- **Projeto** — cliente_id, tipo_projeto, analista_responsavel_id, data_recebimento, data_art,
  data_encaminhado, **numero_solicitacao**, status
  (`RECEBIDO` → `AGUARDANDO_ENVIO` → `ENCAMINHADO` → `APROVADO` | `REPROVADO` →
  `REENCAMINHADO`), motivo_reprova, data_aprovacao
  - `RECEBIDO`: analista recebeu o projeto (status inicial, criado automaticamente quando a
    pendência do cliente é resolvida) — `data_encaminhado` ainda nula
  - **tipo_projeto** tem exatamente **três** valores (confirmado com o usuário):
    `PROJETO_INICIAL`, `AMPLIACAO`, `CORRECAO`. Os seis da V1 vieram das abas da planilha, que
    separavam *casos operacionais* ("uma placa a mais", "mudança de inversor 5kW") e não tipos de
    projeto — todos são, para a Coelba, ou ampliação da usina existente ou correção de projeto
  - **numero_solicitacao**: o número que a Coelba devolve ao receber o projeto. É a chave que vai
    casar o e-mail diário de status com o registro daqui (seção 9) — sem ela a automação teria de
    casar por nome de cliente, que é ambíguo. Preenchido no `/encaminhar`, porque é lá que ele
    passa a existir; `q` da listagem busca por ele além de nome e UC. Sem UNIQUE: reenvio pode
    receber outro número, e a planilha traz o campo irregular
  - **potencia_kwp**: porte da usina, para o gestor somar kWp homologado por período
  - `AGUARDANDO_ENVIO`: projeto já preenchido, mas ainda não enviado à Coelba por algum motivo
    operacional. **Não é o estado do cliente com débito**: débito não pausa o projeto, ele
    bloqueia o envio (ver "Débito" abaixo)
- **Projeto.data_instalacao** — quando a usina foi instalada. **Entrada manual, feita na etapa de
  vistoria** e não por quem homologa (decisão do usuário): o projeto aprovado cai na fila da
  Vistoria, e é lá que se registra a instalação e depois se solicita a vistoria. O endpoint
  continua sendo `POST /api/projetos/{id}/registrar-instalacao` — é campo do Projeto —, mas o
  único caminho pela tela é o módulo de Vistoria. É campo, e não status, porque o status
  acompanha a homologação na Coelba enquanto a instalação é evento de campo; misturar os dois na
  mesma máquina de estados confundiria coisas diferentes. Sem essa data, solicitar vistoria é
  bloqueado (409 `PROJETO_SEM_INSTALACAO`)
- **Vistoria** — projeto_id, data_solicitacao, status (`SOLICITADA` → `APROVADA` | `REPROVADA`,
  e `REPROVADA` → `SOLICITADA` para o reenvio após correção), data_resultado. Reprova e nova
  solicitação ficam no **mesmo registro**, para o histórico mostrar quantas idas e vindas o
  cliente teve em vez de espalhar em vistorias soltas
- **Unificacao** — cliente_id, cidade, projetista_id, informações, `feita` (bool),
  `desligamento_status`, `desligamento_solicitado_em`, `desligamento_concluido_em`.
  <p>
  `feita` é marco simples: unificou ou não. Já o desligamento do medidor unificado é um
  **ciclo de solicitar e aguardar retorno** (esclarecido pelo usuário): terminada a instalação,
  confere-se se a unificação foi feita e, se sim, solicita-se o desligamento do medidor
  unificado e aguarda-se. Estados: `NAO_SOLICITADO → SOLICITADO → CONCLUIDO`, com
  `OS_ABERTA` como desvio para quando **a equipe de campo não realiza** o desligamento.
  <p>
  ⚠️ Isto **reverteu uma decisão anterior**: a Unificação era documentada aqui como "o único
  módulo sem máquina de estados nem evento de domínio", porque só tinha dois booleanos
  independentes. Um booleano não representa "solicitado, aguardando" — que é exatamente o
  estado onde o caso se perde de vista —, então o desligamento ganhou máquina de estados,
  evento e auditoria como os outros módulos. As datas permitem medir a espera, que é o número
  que hoje ninguém tem.
  <p>
  Solicitar o desligamento **exige `feita = true`** (409 `UNIFICACAO_NAO_FEITA`): pedir antes
  desligaria um medidor de que o cliente ainda depende. As filas saem de filtro: `feita=false`
  (falta unificar), `feita=true&desligamentoStatus=NAO_SOLICITADO` (falta pedir) e
  `desligamentoStatus=SOLICITADO,OS_ABERTA` (aguardando retorno)
- **Usuario** / **Papel** / **Permissao** — RBAC (ver seção 4)
- **HistoricoStatus** — tabela de auditoria (entidade_tipo, entidade_id, status_anterior,
  status_novo, timestamp, usuario_id) — **necessária para calcular todas as métricas do
  dashboard**, já que a planilha guarda só a data de cada evento pontual, não um histórico
  genérico

**Integração entre abas/etapas** (requisito do usuário): a mudança de status de uma entidade
deve disparar o avanço automático para a próxima. Ex.: `Pendencia.status = RESOLVIDA` cria/ativa
automaticamente o registro correspondente em `Projeto` com status inicial. Implementado via
evento de domínio (Spring `ApplicationEventPublisher`) — não hardcoded em controller.

**Regra arquitetural (não quebrar)**: toda transição de status passa pelo service do módulo
(`PendenciaService.atualizarStatus`, `ProjetoService.atualizarStatus`), que é o **único** ponto
que publica o evento de domínio. Nenhuma mudança de status via `repository.save()` direto.
Isso é o que faz a automação e a auditoria funcionarem igual independente da origem da mudança
— tela hoje, e-mail do Gmail ou webhook do Nectar amanhã (ver seção 9).

Desenho dos eventos (implementado):

- `common/event/EntidadeStatusEvent` — interface comum a todos os eventos de status
- `PendenciaStatusChangedEvent` / `ProjetoStatusChangedEvent` — eventos concretos (`record`),
  vivem no pacote do módulo que os publica
- `historico/HistoricoStatusEventListener` — `@EventListener` síncrono sobre a interface
  genérica; grava em `historico_status` na mesma transação (nunca há transição sem auditoria)
- `projeto/PendenciaResolvidaListener` — `@TransactionalEventListener(AFTER_COMMIT)`; cria o
  `Projeto` com status `RECEBIDO` só quando a pendência vira `RESOLVIDA`. Após o commit para
  não criar projeto órfão se a transação da pendência for revertida

⚠️ **Armadilha do `AFTER_COMMIT` (já custou um bug silencioso)**: todo listener nessa fase que
escreve no banco **precisa** de `@Transactional(propagation = REQUIRES_NEW)`. Em `AFTER_COMMIT`
a transação original ainda está ligada à thread, então um `@Transactional` comum (REQUIRED)
entra nela — já commitada — e o insert é **descartado em silêncio, sem exceção**. Vale para os
futuros listeners de Nectar/Gmail (seção 9). O `REQUIRES_NEW` vai no listener, não no service:
o service precisa continuar podendo participar de uma transação existente quando o webhook do
CRM criar cliente + projeto atomicamente.

Consequência aceita: a criação do Projeto é transação separada da atualização da Pendencia. Se
falhar, a Pendencia fica `RESOLVIDA` e o fluxo pela metade — o preço de não criar projeto órfão.
Se isso virar problema real, a saída é outbox com reprocessamento, não voltar ao mesmo commit.

## 4. RBAC

Hierarquia confirmada:

| Papel | Quem | Acesso |
|---|---|---|
| `ADMINISTRADOR` | João Gabriel | Acesso total ao sistema |
| `GESTOR` | Igor | Acesso total operacional |
| `ANALISTA` | Ivan, Larissa, Camila, Nycole e demais | Papel único — CRUD nas etapas do fluxo (pendência, débito, projeto, vistoria, unificação), sem distinção por especialidade dentro do sistema |

**Decisão (confirmada com o usuário)**: a autenticação entra **junto com os controllers REST**,
não depois. Motivo: colocar depois exige revisitar todo controller para pôr `@PreAuthorize` e
reescrever os testes de controller (que mudam de forma com a segurança ligada); além disso o
sistema guarda dado de cliente final e informação financeira num VPS exposto à internet, e os
itens 10–12 do checklist da seção 8 pressupõem que a autenticação existe.

**Implementado** (fase 2): Spring Security + JWT, `@PreAuthorize` por método via as
meta-anotações `@PodeLer`, `@PodeEscrever` e `@SomenteAdministrador` de `common/security` — a
matriz fica num lugar só, em vez de repetida em ~30 métodos. Tabelas `papel`/`permissao` N:N
com `usuario` criadas na V1, papéis semeados na V2.

⚠️ O `PendenciaControllerRbacTest` não é opcional: se o `JwtGrantedAuthoritiesConverter` deixar
de ler o claim `papeis` com prefixo `ROLE_`, **todo** `hasRole` passa a negar em silêncio (o
default do Spring lê `scope` e prefixa `SCOPE_`). Esse teste é o que acusa a regressão.

### Matriz de permissões (item 3 do checklist da seção 8)

Duas decisões de negócio confirmadas com o usuário e refletidas abaixo:

1. **ANALISTA vê todos os clientes**, não só os atribuídos a si. É o que resolve a dor da
   planilha (visão consolidada; ninguém trava esperando o colega voltar de férias) e dispensa
   filtro por usuário nas consultas — logo, o RLS do item 5 da seção 8 continua desnecessário.
2. **ANALISTA não apaga nada.** Registro errado é cancelado por status (ex.: `CANCELADA` em
   Pendencia), preservando `historico_status` e mantendo as métricas do gestor confiáveis.
   `DELETE` de verdade fica só com `ADMINISTRADOR`.

| Recurso | Ler | Criar / Editar | Apagar |
|---|---|---|---|
| Cliente | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| Pendencia | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| Debito | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| Projeto | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| Vistoria | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| Unificacao | todos os papéis | ANALISTA, GESTOR, ADMIN | ADMIN |
| `historico_status` | todos os papéis (histórico de um registro e consulta agregada) | **ninguém via API** — escrito exclusivamente pelo listener de domínio | ninguém |
| Dashboard (seção 5) | todos os papéis | — | — |
| Usuário / papéis | GESTOR, ADMIN | GESTOR, ADMIN | ADMIN |

⚠️ **O GESTOR entrou na gestão de usuários em 16/09/2026**, junto com a remoção do login Google
(seção 10). O motivo é consequência direta: sem auto-cadastro, cadastrar gente deixou de ser
tarefa eventual de configuração e virou trabalho de operação — deixá-la só com o ADMINISTRADOR
faria toda contratação esperar por uma pessoa. A meta-anotação é `@GerenciaUsuarios`.

Duas fronteiras que a abertura obrigou a criar, ambas no `UsuarioService` (não no controller: o
`@PreAuthorize` sabe dizer *quem entra no endpoint*, não *o que pode ser feito lá dentro*):

- **Gestor não concede nem altera o papel ADMINISTRADOR**, e não redefine senha nem desativa a
  conta de um administrador. Sem isso ele criaria uma conta de admin para si, entraria por ela e
  apagaria registros — e a linha "Apagar: só ADMIN" desta tabela viraria decoração.
- **Ninguém desativa a própria conta**, que só produziria um administrador trancado do lado de
  fora.

**Excluir usuário continua só do ADMINISTRADOR**: apagar quem já aparece no `historico_status`
falha com 409 (FK) e destruiria a auditoria que sustenta o dashboard. O caminho da operação é
desativar.

Regra que não pode ser violada: `historico_status` **não tem endpoint de escrita**. É populado
só pelo `HistoricoStatusEventListener`. Um POST que permita inserir histórico à mão destrói a
confiabilidade de todas as métricas da seção 5.

## 5. Dashboard

Todas as métricas abaixo dependem de `HistoricoStatus` com timestamps confiáveis:

| Métrica | Cálculo (baseado no histórico) |
|---|---|
| Tempo médio sem ninguém mexer no cliente | `primeira_interação_ts - data_pagamento` |
| Tempo médio de resolução de pendência | `resolvido_em - solicitado_em` (Pendencia) |
| Tempo médio recebimento → envio do projeto | `data_encaminhado - data_recebimento` (Projeto) |
| Tempo médio para aprovação | `data_aprovacao - data_encaminhado` (última vez encaminhado) |
| Tempo médio parado por débito | `debito_quitado_em - debito_detectado_em` |
| Tempo médio para solicitar vistoria pós-instalação | `data_solicitacao_vistoria - data_instalado` |
| Tempo médio de ciclo completo | `data_aprovacao_vistoria - data_recebimento_projeto` |
| Quantitativo: pendências resolvidas | `COUNT(Pendencia WHERE status = RESOLVIDA)` |
| Quantitativo: projetos aprovados | `COUNT(Projeto WHERE status = APROVADO)` |
| Quantitativo: clientes com débito parado | `COUNT(DISTINCT cliente_id) WHERE status = ATIVO` |
| Quantitativo: travados na pendência / na homologação | o mesmo, recortado por `tipo` |
| Quantitativo: projetos encaminhados | `COUNT(Projeto WHERE status = ENCAMINHADO)` |
| Quantitativo: projetos reprovados | `COUNT(Projeto WHERE status = REPROVADO)` |
| Quantitativo: vistorias solicitadas | `COUNT(Vistoria)` |
| Tempo médio de espera do desligamento | `desligamento_concluido_em - desligamento_solicitado_em` |
| Quantitativo: desligamentos aguardando / com O.S. / concluídos | por `desligamento_status` |

### Implementação (feito)

`GET /api/dashboard?de=&ate=&analistaId=` devolve as 13 métricas numa resposta só — a tela
mostra todas juntas, e 13 rotas fariam o frontend orquestrar 13 chamadas para montar uma página.

**`analistaId` recorta tudo por pessoa** (pedido do usuário em 16/09/2026), e o detalhe que faz
a implementação: *não existe uma coluna de responsável*, existe uma por etapa —
`pendencia.responsavel_id`, `projeto.analista_responsavel_id`, `debito.consultado_por_id`,
`unificacao.projetista_id`. Cada consulta aplica a sua, e as que saem do `historico_status`
chegam à pessoa por um **LEFT JOIN** com a entidade: inner join descartaria o histórico órfão
(seção 11) e mudaria os números do caso sem filtro, que é o normal.

- Linha **sem responsável sai do recorte** quando um analista é escolhido: "o que passou pelas
  mãos da Larissa" não inclui o que não passou pelas mãos de ninguém. O projeto sem analista tem
  lugar próprio (a faixa "Sem analista atribuído" e o buraco da seção 11).
- "Tempo sem ninguém mexer no cliente" é atribuído a **quem fez a primeira interação** — daí o
  `DISTINCT ON` na consulta, que um `MIN(criado_em)` agrupado não daria (devolve a data sem a
  pessoa). Lido como "quanto tempo ficaram na gaveta os clientes que ela acabou pegando".
- `analistaId` inexistente responde **404**, e não tudo zerado: zero é uma resposta legítima
  ("não fez nada no período") e a tela não teria como distinguir as duas coisas.
- A resposta devolve `filtro: { analistaId, analistaNome }` para a tela rotular os números com o
  recorte a que pertencem — "3 projetos aprovados" diz coisas bem diferentes com e sem filtro de
  pessoa.

⚠️ **Aberto a todos os papéis** (`@PodeLer`), decisão do usuário em 09/09/2026. Antes era
restrito a GESTOR/ADMIN, com o argumento de que o dashboard expõe o desempenho por analista e
não seria informação que o próprio analista precisa ver. A decisão foi revista: são os mesmos
números que a operação inteira usa para saber onde o fluxo está travando, e escondê-los do
analista deixava metade da equipe trabalhando sem enxergar a fila. Com isso a matriz da seção 4
voltou a ser uniforme na leitura, e a anotação `@SomenteGestor` foi removida por não ter mais
nenhum uso — se a matriz divergir de novo, ela se recria em `common/security`.

- **Cada métrica é recortada pela sua própria data de referência** — aprovados pela data de
  aprovação, resolvidas pela data de resolução. É o que responde "no período X, como foi o
  desempenho", em vez de misturar recortes.
- **Tempo médio nulo ≠ zero**: nulo significa "não houve caso no período". Devolver 0 faria o
  gestor ler "instantâneo" onde não há dado.
- `clientesComDebitoAtivo` **ignora o período** de propósito: é a situação de agora, "quantos
  estão travados neste momento". Conta `DISTINCT cliente_id`: com uma linha de débito por tipo,
  `COUNT(*)` passaria a contar débitos, e a métrica se chama *clientes* — cliente travado nas
  duas etapas contaria duas vezes. Acompanham-na `clientesTravadosNaPendencia` e
  `clientesTravadosNaHomologacao`, que é o recorte que o financeiro usa para saber onde atacar.
- `projetosReprovados` sai do `historico_status`, porque não existe coluna de data de reprova.
- Agregações em SQL nativo (`DashboardRepository`), não em Java: calcular média carregando todas
  as linhas para memória não escala.
- ⚠️ O `ocorrido_em` do evento de débito é a **data da consulta**, não a da digitação. Registrar
  o instante da digitação faria a métrica de tempo parado medir agilidade de digitação, e a
  zeraria em qualquer importação retroativa — foi o que aconteceu com os dados de exemplo antes
  da correção.

## 6. Arquitetura técnica

- **Backend**: Java 21, Spring Boot 4.1.1 (WebMVC, Security, Data JPA, Validation), Maven
- **Banco**: PostgreSQL 16, migrações com Flyway (`src/main/resources/db/migration`)
- **JPA**: `ddl-auto=validate` — divergência entre entidade e migration quebra o boot de
  propósito; o schema é sempre da migration, nunca do Hibernate
- **Auth**: JWT HS256 emitido por nós (access 15 min + refresh 8h), RBAC via `@PreAuthorize`.
  Simétrico porque não há terceiro validando nosso token — chave assimétrica só adicionaria a
  operação de gerar/guardar/rotar PEM. Um único fluxo de login (e-mail e senha) e um único
  emissor (`TokenService`). Ver seção 10.
- **API**: springdoc-openapi **3.1.0** (versão explícita no `pom.xml` — o Boot não a gerencia;
  a linha 2.x não suporta Boot 4). `/swagger-ui.html` e `/v3/api-docs`, desligados no perfil
  `prod`. Contrato commitado em `docs/api/openapi.json`; regerar com a app no ar:
  `curl -s localhost:8080/v3/api-docs -o docs/api/openapi.json`
- **Testes**: JUnit 5 + AssertJ + Mockito, **Testcontainers com Postgres real** (não H2, porque
  o schema usa `CHECK`/`GENERATED AS IDENTITY` específicos do Postgres). `mvn test` exige Docker
  rodando. Slices de persistência usam a anotação própria `@RepositoryTest`, que já importa o
  Testcontainers e o `JpaAuditingConfig`.

### Pegadinhas do Spring Boot 4.1 que já custaram tempo aqui

Cada item abaixo quebrou o build ou a aplicação neste projeto — não confie na memória de Boot 3.x:

- `@DataJpaTest`, `@WebMvcTest`, `AutoConfigureTestDatabase` e `TestEntityManager` **mudaram de
  pacote**: `org.springframework.boot.{data.jpa,webmvc,jdbc,jpa}.test.autoconfigure`. O prefixo
  `org.springframework.boot.autoconfigure.*` foi abandonado.
- `@MockBean` foi **removido** → `@MockitoBean`
  (`org.springframework.test.context.bean.override.mockito`).
- Módulos do **Testcontainers 2.x ganharam prefixo**: `testcontainers-postgresql`,
  `testcontainers-junit-jupiter`.
- Starters OAuth2 renomeados: use `spring-boot-starter-security-oauth2-resource-server`; o
  `spring-boot-starter-oauth2-resource-server` está **deprecado**.
- **Jackson 3**: o runtime virou `tools.jackson.*` e `WRITE_DATES_AS_TIMESTAMPS` saiu de
  `SerializationFeature` para `DateTimeFeature`, já **desabilitado por padrão** (datas saem em
  ISO-8601 sem configurar nada). Configurar `spring.jackson.serialization.write-dates-as-timestamps`
  **derruba a aplicação no boot**.
- `@EnableJpaAuditing` **não** pode ficar na classe principal: ela exige metamodelo JPA e
  quebraria todo `@WebMvcTest`. Fica em `common/config/JpaAuditingConfig`, importada
  explicitamente pelo `@RepositoryTest` (o slice do `@DataJpaTest` não faz scan de
  `@Configuration`).
- **`./config/application.properties` é lido pelos testes também**, e com precedência maior que
  o classpath — então valor de conveniência de desenvolvimento vaza para dentro do Testcontainers
  (ligar `solarsync.dados-de-exemplo` ali quebrou 14 testes que conferem contagem). Todo
  `@SpringBootTest` precisa de `properties = "solarsync.dados-de-exemplo=false"`; está explicado
  em `common.AbstractIntegrationTest`.
- **Não** criar `src/test/resources/application.properties`: um arquivo com esse nome
  **sombreia** o `application.properties` principal em vez de complementá-lo, e a configuração
  real deixa de ser validada pelos testes — foi assim que a propriedade Jackson inválida passou
  por 71 testes verdes e só apareceu ao subir a aplicação. O
  `spring.docker.compose.skip.in-tests` já é `true` por padrão, então o arquivo era
  desnecessário.
- `Specification.where(...)` saiu de cena → `Specification.allOf(...)` / `unrestricted()`.
- `@Builder` do Lombok **não** cobre campos herdados (o `id` do `BaseEntity`): em teste, use
  `entidade.setId(...)` depois do `build()`.
- **Frontend**: repositório separado — pasta local `solarsync-front`, remoto
  `github.com/gmarqueszx/solarsync-web.git` (o nome do remoto ficou diferente da pasta).
  React + TypeScript + Vite + Tailwind, estilo Navan em paleta verde (ver seção 7), consumindo
  esta API. **Ele tem o próprio `CLAUDE.md`**, que é a fonte de verdade das decisões de
  frontend — não duplique regra de negócio lá, porque as duas cópias divergem (foi o que
  aconteceu: o arquivo de lá era uma cópia deste, anterior a todas as decisões de autenticação,
  débito e status de projeto)
- **Infra local**: Docker Compose (API + Postgres), alinhado ao ambiente já usado no VPS Contabo
- **Estrutura de pacotes** (implementada):
  ```
  com.conectsol.solarsync
  ├── cliente
  ├── pendencia
  ├── debito
  ├── projeto
  ├── vistoria
  ├── unificacao
  ├── auth (usuario, papel, permissao)
  │   ├── config    (SecurityFilterChain, CORS, conversor de authorities)
  │   ├── jwt       (chave, TokenService, validador de tipo de token)
  │   ├── login     (login por senha + renovação + AutenticacaoController)
  │   └── dto
  ├── historico (auditoria de status)
  ├── dashboard          (agregações em SQL nativo sobre historico_status)
  ├── common
  │   ├── config    (WebMvc, JpaAuditing, OpenAPI)
  │   ├── security  (UsuarioAutenticado + resolver, meta-anotações de RBAC)
  │   ├── web       (PaginaResponse, ApiExceptionHandler, MotivoRequest)
  │   ├── event     (EntidadeStatusEvent)
  │   └── exception
  └── integracao         (futuro — nectar, gmail; ver seção 9)
  ```
  Cada módulo de etapa segue o mesmo padrão: entidade `extends BaseEntity` + enums de status +
  `JpaRepository` (+ `JpaSpecificationExecutor` onde há filtro) + `Specs` + service + DTOs em
  `<modulo>/dto` + controller.

### Convenções da API (implementadas nas etapas 1–3)

- Prefixo `/api`. Recursos em pt-BR, coerentes com o domínio.
- **Transições de status são endpoints de ação** (`POST /api/pendencias/{id}/resolver`,
  `/api/projetos/{id}/reprovar`). O `PUT` do recurso **não aceita `status`** — é o que impede o
  frontend contornar o service e, com ele, a automação e a auditoria.
- Mudança para o status atual é **no-op idempotente**: responde 200 e não publica evento, para
  um duplo clique não gerar linha duplicada em `historico_status` e distorcer as métricas.
- Máquina de estados nos próprios enums (`StatusPendencia.podeIrPara`,
  `StatusProjeto.podeIrPara`), validada no service. Transição inválida → **409
  `TRANSICAO_INVALIDA`**. Permissiva de propósito (o processo real da Coelba não é linear);
  barra o que corromperia o dashboard, como aprovar projeto nunca encaminhado. Válvula de
  escape: `POST /api/projetos/{id}/corrigir-status`, só ADMIN, com justificativa, mantendo a
  auditoria — necessária para a importação da planilha, onde os dados chegam fora de ordem.
- **Débito bloqueia o envio, não o projeto** (decisão do usuário). O projeto nasce e existe em
  `RECEBIDO` mesmo com o cliente devendo — é assim que o gestor vê o cliente travado e o
  dashboard consegue medir há quanto tempo, em vez de o cliente desaparecer da tela. O que
  falha é `/encaminhar` e `/reencaminhar`. A guarda está em `ProjetoService`, não no controller,
  para valer também quando a origem for o Gmail ou o CRM.
- **Cada etapa exige a consulta do seu tipo de débito**, e recusa com dois códigos diferentes:
  **409 `DEBITO_NAO_CONSULTADO`** (ninguém olhou) e **409 `CLIENTE_COM_DEBITO`** (olhou e o
  cliente deve). Separar os dois importa porque juntá-los mandaria a analista cobrar um cliente
  que talvez não deva nada.

  | Ação | Tipo consultado |
  |---|---|
  | `POST /api/pendencias/{id}/resolver` | `PENDENCIA` |
  | `POST /api/projetos/{id}/encaminhar` e `/reencaminhar` | `HOMOLOGACAO` |

  ⚠️ Isto **reverteu** a assimetria anterior, em que o envio à Coelba tratava "sem consulta" como
  sem débito para não travar cliente novo. Motivo: na operação real é o projetista quem consulta
  o débito ao receber o cliente — essa consulta é um passo do fluxo, não burocracia, e sem
  exigi-la o caso que passava batido era exatamente o cliente que ninguém tinha olhado.
  Consequência prática: **todo cliente novo precisa de uma consulta de homologação registrada
  antes de o projeto ir à Coelba**, e o importador da planilha (passo 7) terá de registrá-las.
  A consulta de um tipo não vale pelo outro.
- `Debito` publica `DebitoStatusChangedEvent`, e é do `historico_status` que sai a **média** do
  dashboard "tempo médio parado por débito" (subtraindo `null → ATIVO` de `ATIVO → QUITADO`).
  Reconsultar e achar a mesma situação **não** publica evento: só atualiza `ultima_consulta_em`,
  senão o tempo parado seria recontado a cada consulta. O `diasParado` **por linha** vem da
  coluna `detectado_em`, não do histórico: a listagem precisa do número em toda linha, e um
  subselect no histórico por linha custaria caro sem responder nada a mais.
- Erros em **`ProblemDetail` (RFC 9457)** com a propriedade `codigo` (string estável para o
  frontend ramificar sem parsear texto) e `erros[]` nas falhas de validação. O `SecurityConfig`
  delega 401/403 do filtro ao `handlerExceptionResolver`, então erro de filtro e erro de
  controller têm o **mesmo formato**.
- Paginação em `PaginaResponse<T>` próprio (`conteudo`, `pagina`, `totalElementos`, …) — nunca
  o `PageImpl` do Spring Data, cujo JSON é instável entre versões.
- Response DTO tem `static de(Entidade)`; request DTO é dado burro interpretado pelo service.
  Entidade JPA nunca cruza a fronteira HTTP.
- **Invariante**: os `@ManyToOne` de `Pendencia`/`Projeto` são EAGER (default do JPA), o que
  torna seguro mapear para DTO no controller mesmo com `open-in-view=false`. Se algum virar
  LAZY, o mapeamento tem de migrar para dentro do `@Transactional` do service.

## 7. Design system

Referência: estilo Navan (SaaS enterprise), adaptado para paleta verde a pedido do usuário.

- **Cor primária**: `#149911` (botões, links, estados ativos) — hover/escuro `#256D1B`
- **Sidebar/header escuro**: `#244F26`
- **Neutro de apoio** (texto secundário, bordas): `#424342`
- **Destaque pontual** (badge "novo", indicador ativo — nunca fundo de botão ou texto): `#1EFC1E`
- **Neutros**: branco para cards e superfícies de conteúdo, cinza claro para o fundo da página,
  quase-preto (`#13151A`-like, no estilo Navan) reservado para sidebar/header ou modo escuro
- **Tipografia**: sans-serif limpa (Inter ou equivalente), pesos 400/500 apenas, tamanhos
  moderados — nada de exagero decorativo
- **Layout**: sidebar de navegação fixa + topbar, conteúdo em cards com `border-radius` generoso
  (12-16px), tabelas com divisores sutis (sem bordas pesadas), badges em formato pílula para
  status (ex.: pendente / resolvido / aprovado / reprovado) coloridos por semântica dentro da
  escala verde + neutros
- **Componentes-chave que o SolarSync precisa**: sidebar na ordem do trabalho (Dashboard,
  Clientes, Débitos, Pendências, Projetos, Vistoria, Unificação, e Usuários numa seção
  "Administração"), tabela de listagem com filtro, **ordenação por coluna** e paginação, cards de
  métricas (KPI) no dashboard, badges de status, formulário de detalhe/edição por entidade
  - A consulta de débito vem logo depois de Clientes por pedido do usuário (16/09/2026): na
    prática a agência virtual é checada assim que o cliente entra, e o resultado é o que decide
    se a pendência anda e se o projeto pode ser enviado
  - **Sem rótulo de etapa nos módulos.** "Etapa 2 & 3" descrevia o desenho do processo, não o
    trabalho de quem abre a tela, e numerava um fluxo que na operação não é linear

Pendente: gerar escala completa (50-900) a partir de `#149911` no uicolors.app quando for
implementar o CSS/tema.

## 8. Checklist de produção (baseado no guia dos 12 itens)

Adaptação dos 12 itens do guia Mestre-Code para o contexto do SolarSync — sistema interno de
uma única empresa (ConectSol), não SaaS multi-cliente. Isso muda a prioridade de alguns itens.

| # | Item | Aplica? | Nota para o SolarSync |
|---|---|---|---|
| 1 | PRD | **Sim** | Este próprio CLAUDE.md cumpre esse papel — manter atualizado a cada decisão |
| 2 | Mapa do sistema (UML) | **Sim** | Falta gerar: diagrama de classes das entidades da seção 3 e diagrama de sequência do fluxo de status (pendência → projeto → vistoria) |
| 3 | RBAC (matriz completa) | **Sim — feito e implementado** | Matriz ação × papel na seção 4, aplicada via meta-anotações de `@PreAuthorize` e coberta por teste por papel |
| 4 | Multi-tenancy | **Não se aplica** | Sistema é de uma empresa só (ConectSol), não atende múltiplos clientes-empresa na mesma base. Não criar isolamento por tenant |
| 5 | RLS no banco | **Não se aplica mais** | O único caso de uso era reforçar "analista só vê clientes atribuídos a si" — e ficou decidido (seção 4) que ANALISTA vê todos os clientes. Sem restrição por linha a aplicar, RLS não tem o que proteger aqui |
| 6 | Nenhuma senha no código | **Sim — feito** | `.env` e `*.pem` no `.gitignore`, `.env.example` como referência. Segredo JWT e senha inicial do admin vêm do ambiente; sem eles a app sobe em modo dev (segredo aleatório) em vez de embutir credencial |
| 7 | Arquitetura modular (liga/desliga por cliente) | **Não se aplica como catálogo comercial** | Não há "clientes-empresa" comprando módulos. Mas a separação em pacotes por etapa (seção 6) já cumpre o espírito de baixo acoplamento entre módulos |
| 8 | Botão de reportar problema | **Sim, recomendado** | Útil dado que analistas vão operar o sistema diariamente — botão de feedback com captura de tela/contexto ajuda a substituir o "manda áudio de 3 minutos" |
| 9 | Testes automáticos | **Sim, obrigatório** | Crítico especificamente para a regra de integração entre etapas (seção 3: pendência resolvida → cria projeto automaticamente) e para o cálculo das métricas do dashboard — são os dois pontos onde um bug silencioso derruba a confiança do gestor no sistema |
| 10 | Auditoria de segurança | **Sim, antes de ir ao ar** | Sistema guarda dado de cliente final (nome, débito, projeto) — mesmo sendo uso interno, uma auditoria básica (dependências desatualizadas, endpoints sem autenticação, RBAC sem furo) antes do deploy no VPS Contabo é razoável |
| 11 | WAF / rate limiting | **Sim** | VPS Contabo expõe a aplicação à internet — recomentélo Cloudflare (gratuito) na frente, rate limit no endpoint de login |
| 12 | HTTPS/TLS | **Sim, obrigatório** | Certificado (Let's Encrypt) + redirecionamento forçado HTTPS no domínio usado no VPS Contabo |

**Resumo prático**: dos 12, os itens 4 e 7 não se aplicam no sentido original (são pensados pra
SaaS multi-cliente); o 5 é opcional; os outros 9 valem para o SolarSync.

## 9. Integrações externas (fase posterior)

Objetivo declarado do projeto: sair da planilha 100% manual para um sistema com margem de
automação. As integrações abaixo **não estão implementadas** — mas o desenho de eventos da
seção 3 já é o ponto de extensão delas, então nenhuma exige refatoração do domínio.

| Integração | Direção | Como encaixa no desenho atual |
|---|---|---|
| **Nectar (CRM)** — criar cliente/projeto quando negócio fecha | Entrada | Webhook do Nectar → chama os services do domínio (`ClienteRepository` + `ProjetoService`), mesmo caminho da tela |
| **Nectar (CRM)** — refletir status de volta pro comercial | Saída | Novo `@Component` em `integracao/nectar` com `@TransactionalEventListener(AFTER_COMMIT)` sobre `EntidadeStatusEvent`. Zero mudança em `pendencia`/`projeto` |
| **Gmail** — ler e-mail diário da Coelba (aprovado/reprovado/em análise) | Entrada | Job em `integracao/gmail` faz parsing do e-mail e chama `ProjetoService.atualizarStatus(...)` — o mesmo método que a API usa, então auditoria e automação disparam igual |

Ponto de atenção da etapa 1 do fluxo (seção 1): hoje o analista atualiza pendência resolvida em
dois lugares (planilha + Trello). O SolarSync elimina a planilha; decidir depois se o Trello
sai de cena ou vira mais um listener de saída.

## 10. Autenticação: contrato e ambiente

**Um único método de login**, e nenhum auto-cadastro:

| Endpoint | Corpo | Observação |
|---|---|---|
| `POST /api/auth/login` | `{email, senha}` | senha em BCrypt força 10 |
| `POST /api/auth/refresh` | `{refreshToken}` | relê o usuário; inativo não renova |
| `GET /api/auth/eu` | — | o frontend usa os papéis para montar a sidebar |

Resposta: `{accessToken, refreshToken, tipo: "Bearer", expiraEmSegundos, usuario}`.

⚠️ **O login com Google foi removido em 16/09/2026** (decisão do usuário). Saíram o endpoint
`POST /api/auth/login/google`, o pacote `auth/google` inteiro (`VerificadorIdTokenGoogleNimbus`
e o `GoogleProperties`), o `LoginGoogleService` e as propriedades `solarsync.google.*`. Nada
disso tinha substituto a construir: o fluxo já era "não cadastra ninguém, só reconhece quem já
existe", então tirá-lo não fechou porta nenhuma — deixou uma porta só.

Duas consequências que o código teve de acompanhar:

1. **A senha virou obrigatória no cadastro** (`UsuarioCriarRequest`). Ela era opcional porque
   quem não tinha senha entrava pelo Google; sem ele, um usuário sem senha é uma conta que não
   entra por caminho nenhum. Contas antigas nessa situação aparecem na tela de Usuários com o
   aviso "sem senha definida".
2. **Cadastrar gente virou trabalho de operação**, e por isso o GESTOR entrou na gestão de
   usuários (seção 4). O `UsuarioAtualizarRequest` **não tem campo senha**: trocá-la é
   `POST /api/usuarios/{id}/senha`, senão um formulário de correção de nome resetaria o acesso
   de quem esquecesse de preencher o campo.

As migrations `V3__seed_admin.sql` e `V4__unique_email_lower.sql` ainda citam o Google nos
comentários. Ficaram como estão de propósito: editar migration já aplicada quebra o checksum do
Flyway, e o comentário virou contexto histórico, não instrução.

**Revogação de acesso é `usuario.ativo = false`** (`POST /api/usuarios/{id}/desativar`), não
exclusão: o access token morre em ≤15 min e a renovação passa a ser negada, preservando a
auditoria. Não há logout no servidor (a API é stateless; o cliente descarta os tokens).

⚠️ `historico_status.usuario_id` tem FK para `usuario`: apagar um usuário que já aparece no
histórico falha com 409. Desative em vez de apagar — é o caminho previsto.

**Variáveis de ambiente** (ver `.env.example`; nenhuma tem valor no repositório):

| Variável | Efeito se ausente |
|---|---|
| `SOLARSYNC_JWT_SEGREDO` | segredo aleatório no boot + WARN — tokens não sobrevivem a restart (ok em dev, **inaceitável em produção**) |
| `SOLARSYNC_ADMIN_SENHA_INICIAL` | admin da V3 segue sem senha e **ninguém consegue entrar** |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | banco fica vazio (comportamento normal) |
| `SOLARSYNC_CORS_ORIGENS` | `http://localhost:5173` |

**Dados de exemplo** (`SOLARSYNC_DADOS_DE_EXEMPLO=true`, só em dev): `exemplo/DadosDeExemplo`
semeia 20 clientes cobrindo todos os estados do fluxo — pendência aberta/em andamento/resolvida/
cancelada, projeto travado por débito, reprovado, reencaminhado, aprovado, instalado sem
vistoria, ciclo completo com vistoria reprovada e reaprovada, e as duas filas de unificação.
<p>
Passa **pelos services, não por SQL**: é o que faz os eventos dispararem e o
`historico_status` nascer povoado. Semeando por SQL as telas teriam dados mas o dashboard não
teria nada para agregar, e a automação "pendência resolvida → cria projeto" não seria
exercitada. As datas de negócio são retroativas; os timestamps do histórico são do momento da
semeadura. Idempotente. Para limpar em dev: `docker compose down -v`.

**Primeiro acesso**: a V3 semeia só João Gabriel como ADMINISTRADOR, com `senha_hash` nulo.
Como não há auto-cadastro nem login federado, sem nada mais **não haveria como entrar** — e
desde a remoção do Google isso deixou de ser um detalhe de configuração: o `AdminBootstrap`, que
aplica `solarsync.admin.senha-inicial` ao admin uma única vez, é a **única** forma de abrir o
sistema num banco novo. Os demais usuários são cadastrados por ele ou pelo gestor em
`POST /api/usuarios`, que agora tem tela (módulo Usuários & Acesso).

⚠️ O bootstrap **nunca sobrescreve senha já definida** — a guarda existe para não resetar a
senha a cada restart. Consequência prática: trocar o valor configurado num banco que já tem
senha **não tem efeito**, e o login com a senha nova dá 401. O log avisa em INFO. Para trocar
de verdade, use `POST /api/usuarios/{id}/senha`; em dev, `docker compose down -v` recria tudo.

### Rodando local

Criar `config/application.properties` (a pasta `config/` está no `.gitignore`; use o
`.env.example` como referência dos nomes). O Spring Boot lê esse arquivo **automaticamente** —
sem profile e sem variável de ambiente —, então `./mvnw spring-boot:run` já sobe pronto, com
segredo JWT fixo (token sobrevive a restart), senha de admin e dados de exemplo. É o caminho
recomendado: evita depender de sintaxe de variável de ambiente, que difere entre PowerShell e
Bash. Cuidado com o efeito colateral desse arquivo nos testes, descrito na seção 6.

## 11. Buracos conhecidos

Coisas que funcionam como projetado, mas cujo efeito colateral vale ter em vista:

- **Projeto criado pela automação nasce sem analista.** `criarOuAtivarProjetoParaCliente` não
  tem como saber a quem atribuir, então o projeto aparece sem responsável — e pode ficar sem
  dono sem ninguém perceber, que é o tipo de furo que este sistema deveria eliminar. Não existe
  filtro "sem analista" na API; seriam poucas linhas em `ProjetoSpecs` e viraria fila de
  trabalho. O dashboard já mostra uma faixa "Sem analista atribuído" na carga por analista.
- **Apagar um registro deixa histórico órfão.** `historico_status` referencia
  `entidade_tipo` + `entidade_id` sem FK (só `usuario_id` tem FK), então excluir uma pendência
  ou projeto deixa as linhas de auditoria apontando para um id que não existe mais. É coerente
  com preservar auditoria, mas essas linhas entram nas contagens do dashboard que saem do
  histórico (reprovados, reencaminhados, vistorias reprovadas). Mais um motivo para o caminho
  normal ser cancelar por status, não excluir.
- **`historico_status.usuario_id` tem FK para `usuario`**: apagar um usuário que já aparece no
  histórico falha com 409. Desative em vez de apagar. Em teste, isso significa limpar
  `historico_status` antes dos usuários no `@AfterEach` — todo módulo que publica evento cai
  nisso.
- **Endpoint sem tela é endpoint que ninguém usa.** A API tem `POST /api/clientes` desde a
  fase 2, mas o frontend passou por cinco fases sem tela de cadastro de cliente — e como todo
  outro módulo pede um `clienteId`, a interface só funcionava com `SOLARSYNC_DADOS_DE_EXEMPLO`
  ligado. Descoberto testando a interface, não pelos testes: o backend estava verde. O mesmo
  buraco valia para Usuários e **foi fechado em 16/09/2026**: cadastrar analista era um POST no
  Swagger, e com a remoção do login Google isso deixou de ser aceitável — virou o módulo
  Usuários & Acesso. Ao fechar um módulo, conferir se ele tem caminho pela tela, não só rota no
  contrato.
- **Sem rate limit no login** (item 11 do checklist): BCrypt é caro de propósito, e endpoint
  público sem limite é vetor de negação de serviço. Bloqueante para ir ao ar.
- **Mensagem de erro de login não distingue API fora do ar de senha errada.** O `fetch` lança
  `TypeError` quando não alcança o servidor, e o `AuthContext` do frontend trata no `catch`
  genérico — a tela diz "credenciais inválidas" quando o backend está desligado. Confunde, e é
  simples de separar.

## 12. Decisões em aberto com o usuário

Levantadas e ainda sem resposta ao fim da sessão de 03–04/09/2026:

- **Data da ART**: hoje é pedida no modal de "Encaminhar à Coelba", não no cadastro do projeto,
  porque é lá que ela é usada. Se a ART já for conhecida no momento do cadastro, o campo volta
  para o formulário de criação (a API aceita `dataArt` na criação).
- ~~**Formatação de datas no frontend**~~ — **resolvido** (09/09/2026): util compartilhado
  `src/utils/data.ts` no front, `DD/MM/AAAA` para datas e `DD/MM/AAAA HH:mm` em horário de
  Brasília para instantes, aplicado em todos os módulos.

## 13. Próximos passos

1. ~~Modelo de dados + eventos de domínio~~ — **feito** (fase 1)
2. ~~Contratos REST/OpenAPI + controllers de Pendências e Projetos, com JWT e RBAC~~ —
   **feito** (fase 2): mais Clientes e Usuários, contrato em `docs/api/openapi.json`
3. ~~Débito (etapa 2)~~ — **feito**: `PUT /api/debitos/cliente/{clienteId}` registra a consulta,
   e débito ativo bloqueia o envio à Coelba com 409. Revisto em 09/09/2026: **dois tipos de
   débito** (o que trava a pendência e o que trava a homologação), consulta obrigatória nas duas
   etapas, e relógio de tempo parado por cliente
4. ~~Vistoria e Unificação (etapa 4)~~ — **feito**: `data_instalacao` no Projeto (entrada
   manual), vistoria só depois da instalação, e as duas filas de unificação por filtro
5. ~~Dashboard (seção 5)~~ — **feito**: `GET /api/dashboard?de=&ate=&analistaId=`, as 13
   métricas numa resposta só. Aberto a todos os papéis desde 09/09/2026 (antes era só
   GESTOR/ADMIN); recorte por analista desde 16/09/2026
6. ~~Frontend ligado na API~~ — **feito**: as sete telas consumindo o contrato, com login,
   renovação automática de token e RBAC. Ver o `CLAUDE.md` do `solarsync-front`. A tela de
   Clientes entrou depois das outras seis (04/09/2026): o cadastro do cliente é a entrada do
   fluxo, e sem ela a interface só funcionava com os dados de exemplo
7. ~~Correções vindas do uso real (09/09/2026)~~ — **feito**: débito em dois tipos com a tela do
   financeiro, número de solicitação da Coelba, três subtipos de projeto, instalação movida para
   a etapa de vistoria com fila de projetos aprovados, datas em pt-BR, e a lapidação do
   design system do frontend
8. ~~Segunda rodada de uso real (16/09/2026)~~ — **feito**: recorte do dashboard por período
   personalizado e por analista; **login Google removido** e cadastro de usuários aberto ao
   GESTOR, com tela própria; observação da pendência editável em qualquer status; ordenação
   crescente/decrescente por coluna em todas as listagens; e a limpeza da interface (ordem da
   sidebar com Débitos logo após Clientes, fim dos rótulos "Etapa N", fim das referências à
   planilha antiga, título do site só "ConectSol")
9. **Próximo**: script de importação da planilha `PLANILHA_TESTE_-_PROJETOS_.xlsx` (usar
   `POST /api/projetos/{id}/corrigir-status` para os registros que chegam fora de ordem, e a
   data de consulta/solicitação nos módulos que aceitam data retroativa, para as métricas não
   nascerem zeradas, e registrar as consultas de débito dos dois tipos, que agora são exigidas
   para resolver pendência e encaminhar projeto)
10. Antes de ir ao ar: rate limit no `/api/auth/login` (item 11 do checklist — sem ele o BCrypt
   é vetor de DoS), HTTPS/TLS (item 12) e a auditoria de segurança (item 10)
11. Integrações da seção 9 (Nectar, Gmail) — o `numero_solicitacao` do Projeto é a chave que a
    leitura do e-mail da Coelba vai usar para casar o retorno com o registro
