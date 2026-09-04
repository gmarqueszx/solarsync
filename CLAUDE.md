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
- Dashboard gerencial com métricas de tempo de ciclo, restrito ao gestor

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
| `AUMENTO DE POTENCIA`, `AMPLIAÇOES`, `PROJETO COM UMA PLACA A MAIS`, `MUDANÇA DE INVERSOR 5KW`, `INVERSORES SEPARADOS` | Subtipos de projeto | Mesmo ciclo de vida do projeto — devem virar um campo `tipo_projeto`, não abas/tabelas separadas |
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
- **Pendencia** — cliente_id, tipo, status, solicitado_em, resolvido_em, responsavel_id, observação
- **Debito** — cliente_id, status (ativo/quitado), última_consulta_em. Um registro por cliente
  (constraint no banco): é o retrato da última consulta na agência virtual, não um lançamento
  contábil. Reconsultar atualiza o mesmo registro, e o vai-e-vem fica em `historico_status`
- **Projeto** — cliente_id, tipo_projeto (padrão/ampliação/aumento_potência/mudança_inversor/...),
  analista_responsavel_id, data_recebimento, data_art, data_encaminhado, status
  (`RECEBIDO` → `AGUARDANDO_ENVIO` → `ENCAMINHADO` → `APROVADO` | `REPROVADO` →
  `REENCAMINHADO`), motivo_reprova, data_aprovacao
  - `RECEBIDO`: analista recebeu o projeto (status inicial, criado automaticamente quando a
    pendência do cliente é resolvida) — `data_encaminhado` ainda nula
  - **potencia_kwp**: porte da usina, para o gestor somar kWp homologado por período
  - `AGUARDANDO_ENVIO`: projeto já preenchido, mas ainda não enviado à Coelba por algum motivo
    operacional. **Não é o estado do cliente com débito**: débito não pausa o projeto, ele
    bloqueia o envio (ver "Débito" abaixo)
- **Projeto.data_instalacao** — quando a usina foi instalada. **Entrada manual**: a Nycole
  recolhe a informação no grupo "projetos instalados" e registra em
  `POST /api/projetos/{id}/registrar-instalacao`. É campo do Projeto, e não um status, porque o
  status acompanha a homologação na Coelba (decisão deles) enquanto a instalação é evento de
  campo — misturar os dois na mesma máquina de estados confundiria coisas diferentes. Sem essa
  data, solicitar vistoria é bloqueado (409 `PROJETO_SEM_INSTALACAO`)
- **Vistoria** — projeto_id, data_solicitacao, status (`SOLICITADA` → `APROVADA` | `REPROVADA`,
  e `REPROVADA` → `SOLICITADA` para o reenvio após correção), data_resultado. Reprova e nova
  solicitação ficam no **mesmo registro**, para o histórico mostrar quantas idas e vindas o
  cliente teve em vez de espalhar em vistorias soltas
- **Unificacao** — cliente_id, cidade, projetista_id, informações, feita (bool), desligamento
  (bool). Os dois marcos são **independentes e em qualquer ordem**, e a entidade não tem campo
  de status: por isso este é o único módulo sem máquina de estados nem evento de domínio —
  inventar um status só para uniformizar criaria modelagem que o processo real não tem, e o
  dashboard não pede métrica de tempo de unificação. As filas de trabalho saem de filtro:
  `feita=false`, e `feita=true&desligamento=false` para quem ainda tem medidor a desligar
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
| `GESTOR` | Igor | Acesso total operacional + dashboard exclusivo |
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
| `historico_status` | todos os papéis (histórico de um registro); consulta agregada só GESTOR/ADMIN | **ninguém via API** — escrito exclusivamente pelo listener de domínio | ninguém |
| Dashboard (seção 5) | GESTOR, ADMIN | — | — |
| Usuário / papéis | ADMIN | ADMIN | ADMIN |

Regra que não pode ser violada: `historico_status` **não tem endpoint de escrita**. É populado
só pelo `HistoricoStatusEventListener`. Um POST que permita inserir histórico à mão destrói a
confiabilidade de todas as métricas da seção 5.

## 5. Dashboard (exclusivo `GESTOR`)

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
| Quantitativo: clientes com débito parado | `COUNT(Debito WHERE status = ATIVO)` |
| Quantitativo: projetos encaminhados | `COUNT(Projeto WHERE status = ENCAMINHADO)` |
| Quantitativo: projetos reprovados | `COUNT(Projeto WHERE status = REPROVADO)` |
| Quantitativo: vistorias solicitadas | `COUNT(Vistoria)` |

### Implementação (feito)

`GET /api/dashboard?de=&ate=` devolve as 13 métricas numa resposta só — a tela mostra todas
juntas, e 13 rotas fariam o frontend orquestrar 13 chamadas para montar uma página. Restrito a
GESTOR e ADMINISTRADOR (`@SomenteGestor`): o dashboard expõe o desempenho por analista, e não é
informação que o próprio analista precisa ver.

- **Cada métrica é recortada pela sua própria data de referência** — aprovados pela data de
  aprovação, resolvidas pela data de resolução. É o que responde "no período X, como foi o
  desempenho", em vez de misturar recortes.
- **Tempo médio nulo ≠ zero**: nulo significa "não houve caso no período". Devolver 0 faria o
  gestor ler "instantâneo" onde não há dado.
- `clientesComDebitoAtivo` **ignora o período** de propósito: é a situação de agora, "quantos
  estão travados neste momento".
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
  operação de gerar/guardar/rotar PEM. Dois fluxos de login, um único emissor (`TokenService`).
  Ver seção 10.
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
- **Frontend**: estilo Navan em paleta verde (ver seção 7) — repo separado `solarsync-web`,
  consome a API via REST/JSON
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
  │   ├── google    (verificação do ID token)
  │   ├── login     (os dois fluxos + renovação + AutenticacaoController)
  │   └── dto
  ├── historico (auditoria de status)
  ├── dashboard          (reservado — depende de historico_status povoado)
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
  falha é `/encaminhar` e `/reencaminhar`, com **409 `CLIENTE_COM_DEBITO`**. A guarda está em
  `ProjetoService`, não no controller, para valer também quando a origem for o Gmail ou o CRM.
  Cliente sem nenhuma consulta registrada conta como sem débito — barrar por falta de consulta
  travaria todo cliente novo.
- `Debito` publica `DebitoStatusChangedEvent`, e é do `historico_status` que sai a métrica
  "tempo médio parado por débito" (subtraindo `null → ATIVO` de `ATIVO → QUITADO`) — sem
  precisar de colunas de data extra. Reconsultar e achar a mesma situação **não** publica
  evento: só atualiza `ultima_consulta_em`, senão o tempo parado seria recontado a cada consulta.
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
- **Componentes-chave que o SolarSync precisa**: sidebar com os módulos (Pendências, Débitos,
  Projetos, Vistoria, Unificação, Dashboard), tabela de listagem com filtro e paginação, cards de
  métricas (KPI) no dashboard, badges de status, formulário de detalhe/edição por entidade

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
| 6 | Nenhuma senha no código | **Sim — feito** | `.env` e `*.pem` no `.gitignore`, `.env.example` como referência. Segredo JWT, client ID do Google e senha inicial do admin vêm do ambiente; sem eles a app sobe em modo dev (segredo aleatório, Google desabilitado) em vez de embutir credencial |
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

Dois métodos de login, ambos terminando no mesmo emissor (`TokenService.emitirPar`):

| Endpoint | Corpo | Observação |
|---|---|---|
| `POST /api/auth/login` | `{email, senha}` | senha em BCrypt força 10 |
| `POST /api/auth/login/google` | `{idToken}` | ID token do Google Identity Services |
| `POST /api/auth/refresh` | `{refreshToken}` | relê o usuário; inativo não renova |
| `GET /api/auth/eu` | — | o frontend usa os papéis para montar a sidebar |

Resposta: `{accessToken, refreshToken, tipo: "Bearer", expiraEmSegundos, usuario}`.

**Login Google não cadastra ninguém** — três barreiras em `LoginGoogleService`: (1) assinatura
pelo JWKS do Google + `iss` + `aud` = nosso client ID; (2) `email_verified` e domínio em
`solarsync.google.dominios-permitidos`; (3) usuário já existente e `ativo`. O serviço não tem
`save` em nenhum caminho, e há teste com `verify(usuarioRepository, never()).save(any())`
provando isso. Todas as falhas devolvem a mesma mensagem genérica, para não revelar qual
barreira caiu — mesma disciplina no login por senha.

**Revogação de acesso é `usuario.ativo = false`** (`POST /api/usuarios/{id}/desativar`), não
exclusão: o access token morre em ≤15 min e a renovação passa a ser negada, preservando a
auditoria. Não há logout no servidor (a API é stateless; o cliente descarta os tokens).

⚠️ `historico_status.usuario_id` tem FK para `usuario`: apagar um usuário que já aparece no
histórico falha com 409. Desative em vez de apagar — é o caminho previsto.

**Variáveis de ambiente** (ver `.env.example`; nenhuma tem valor no repositório):

| Variável | Efeito se ausente |
|---|---|
| `SOLARSYNC_JWT_SEGREDO` | segredo aleatório no boot + WARN — tokens não sobrevivem a restart (ok em dev, **inaceitável em produção**) |
| `SOLARSYNC_GOOGLE_CLIENT_ID` | login Google desabilitado |
| `SOLARSYNC_ADMIN_SENHA_INICIAL` | admin da V3 segue sem senha (só Google) |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | banco fica vazio (comportamento normal) |
| `SOLARSYNC_CORS_ORIGENS` | `http://localhost:5173` |

**Dados de exemplo** (`SOLARSYNC_DADOS_DE_EXEMPLO=true`, só em dev): `exemplo/DadosDeExemplo`
semeia 15 clientes cobrindo todos os estados do fluxo — pendência aberta/em andamento/resolvida/
cancelada, projeto travado por débito, reprovado, reencaminhado, aprovado, instalado sem
vistoria, ciclo completo com vistoria reprovada e reaprovada, e as duas filas de unificação.
<p>
Passa **pelos services, não por SQL**: é o que faz os eventos dispararem e o
`historico_status` nascer povoado. Semeando por SQL as telas teriam dados mas o dashboard não
teria nada para agregar, e a automação "pendência resolvida → cria projeto" não seria
exercitada. As datas de negócio são retroativas; os timestamps do histórico são do momento da
semeadura. Idempotente. Para limpar em dev: `docker compose down -v`.

**Primeiro acesso**: a V3 semeia só João Gabriel como ADMINISTRADOR, com `senha_hash` nulo.
Como o login Google não faz auto-cadastro, sem nada mais **não haveria como entrar**; por isso
existe o `AdminBootstrap`, que aplica `solarsync.admin.senha-inicial` ao admin uma única vez.
Os demais usuários são cadastrados por ele em `POST /api/usuarios`.

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

## 11. Próximos passos

1. ~~Modelo de dados + eventos de domínio~~ — **feito** (fase 1)
2. ~~Contratos REST/OpenAPI + controllers de Pendências e Projetos, com JWT e RBAC~~ —
   **feito** (fase 2): mais Clientes e Usuários, contrato em `docs/api/openapi.json`
3. ~~Débito (etapa 2)~~ — **feito**: `PUT /api/debitos/cliente/{clienteId}` registra a consulta,
   e débito ativo bloqueia o envio à Coelba com 409
4. ~~Vistoria e Unificação (etapa 4)~~ — **feito**: `data_instalacao` no Projeto (entrada
   manual), vistoria só depois da instalação, e as duas filas de unificação por filtro
5. ~~Dashboard (seção 5)~~ — **feito**: `GET /api/dashboard?de=&ate=`, as 13 métricas numa
   resposta só, restrito a GESTOR/ADMIN
6. **Próximo**: Frontend `solarsync-web` consumindo o contrato de `docs/api/openapi.json`
7. Script de importação da planilha (usar `POST /api/projetos/{id}/corrigir-status` para os
   registros que chegam fora de ordem)
8. Antes de ir ao ar: rate limit no `/api/auth/login` (item 11 do checklist — sem ele o BCrypt
   é vetor de DoS), HTTPS/TLS (item 12) e a auditoria de segurança (item 10)
9. Integrações da seção 9 (Nectar, Gmail)
