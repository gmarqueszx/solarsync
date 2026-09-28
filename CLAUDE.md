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
  - **nectar_oportunidade_id**: preenchido só quando o cadastro veio da sincronização com o CRM
    (seção 9). Índice único **parcial** (V13) — é a idempotência do job, que relê a mesma página
    do Nectar a cada execução. Fica no cliente, e não numa tabela de controle, porque é um dado
    do cliente: responde "de onde veio este cadastro", que é a pergunta de quem vê um cliente
    que ninguém digitou. Está em `ClienteResponse` por isso — a criação do cliente não publica
    evento, então sem esse campo nem o `historico_status` contaria a procedência
  - O `PUT /api/clientes/{id}` **não** mexe em `status_triagem` nem na **prioridade** —
    corrigir telefone não pode, de passagem, apagar o fato de que a Coelba já foi consultada nem
    o pedido de adiantamento de alguém
  - **prioridade / prioridade_motivo / prioridade_observacao / prioridade_definida_em /
    prioridade_definida_por_id / prioridade_data_instalacao** (V16, 22/09/2026): o cliente
    adiantado a pedido. Fica no Cliente, e não em nenhuma entidade de etapa, porque é
    exatamente isso que o requisito pede — "no topo da etapa em que estiver, independentemente
    da etapa atual, e continua no topo quando avançar". As entidades de etapa nascem e morrem ao
    longo do fluxo; o cliente atravessa todas
    - `POST /api/clientes/{id}/prioridade` liga ou **revisa** (chamar de novo troca o motivo sem
      apagar quem pediu primeiro); `POST /api/clientes/{id}/remover-prioridade` encerra
    - O motivo é obrigatório (`MotivoPrioridade`: `INSTALACAO_ADIANTADA`, `PRAZO_CONTRATUAL`,
      `PRAZO_DO_CLIENTE`, `OUTRO`). Prioridade sem motivo não dá para revisar depois — ninguém
      sabe se ainda vale. Sem `podeIrPara`, pela mesma razão do `StatusTriagem`: qualquer motivo
      pode virar qualquer outro
    - ⚠️ **`prioridade_data_instalacao` não é um segundo campo de data de instalação.** O campo
      do fluxo continua sendo `projeto.data_instalacao`, que é o que a vistoria lê e exige. A
      coluna do cliente existe porque a prioridade é marcada **na triagem**, quando em geral
      ainda não há projeto para receber a data: ela espera ali e desce para o projeto assim que
      ele existe (na hora se já existir; no nascimento dele se não). Obrigatória só com
      `INSTALACAO_ADIANTADA` — 409 `PRIORIDADE_SEM_INSTALACAO`
    - A propagação **nunca sobrescreve** data já registrada: quem instalou e anotou na etapa de
      vistoria sabe mais que um pedido de prioridade que pode ser de semanas atrás
    - Encerrar a prioridade **não apaga** a data já propagada: ela é fato de campo, não
      privilégio, e apagá-la travaria a solicitação da vistoria adiante
    - ⚠️ `prioridade_definida_por_id` tem FK para `usuario`: em teste, limpar `cliente` **antes**
      de `usuario`. É a mesma armadilha que `historico_status.usuario_id` já criava
  - **somente_pendencia** (V16): o cliente avulso que o gestor manda ao setor só para resolver
    uma pendência — entrada → pendência → resolvida → **fim**, sem projeto. Flag e não status:
    não é uma etapa, é o desenho do fluxo daquele cliente, decidido na entrada. Fica no
    `ClienteRequest` (cadastro/triagem) de propósito, porque **desmarcá-lo é o caminho** para
    devolver ao fluxo completo o avulso que virou projeto de verdade
  - **banco** (V16): projeto pago por financiamento. Muda uma coisa só, e fora do SolarSync: a
    etapa para onde o cliente volta no Nectar quando o projeto é aprovado (seção 9). Ligado
    automaticamente quando a oportunidade entra pela etapa de banco do CRM, e editável à mão
  - **etiquetas** (`ClienteResponse.etiquetas`): `CRM` e `BANCO`, **derivadas** de `origem` e
    `banco`. Derivadas e não armazenadas é o que atende, por construção, ao "não duplicar
    etiquetas caso a operação seja executada novamente" — uma lista calculada a cada leitura não
    tem como acumular repetição, enquanto uma tabela dependeria de todo caminho de escrita
    lembrar de conferir antes de inserir
- **Pendencia** — cliente_id, tipo, status, solicitado_em, resolvido_em, responsavel_id, observação
  - **Um único status ativo**: `ABERTA` → `RESOLVIDA` | `CANCELADA`, e as duas voltam para
    `ABERTA`. Apontar a pendência na triagem do cliente **é** iniciá-la — dali o cliente já cai
    na fila de Pendências e a solicitação já correu na Coelba
  - ⚠️ `EM_ANDAMENTO` e o endpoint `POST /api/pendencias/{id}/iniciar` (o botão "play" da tela)
    foram **removidos** em 19/09/2026, decisão do usuário. Eram um clique que não mudava nada:
    o relógio da métrica de resolução sempre saiu de `resolvido_em - solicitado_em`, e
    `solicitado_em` é gravado na **criação**. O status a mais só produzia pendência parada em
    `ABERTA` por esquecimento de clicar, indistinguível de trabalho que ninguém pegou — o
    oposto do que o `StatusTriagem` do Cliente faz. O andamento vive na observação e no
    `historico_status`, que é onde cabe texto livre
  - A V14 converte as linhas `EM_ANDAMENTO` em `ABERTA` e aperta o CHECK. As linhas de
    `historico_status` que citam `EM_ANDAMENTO` **ficam**: `status_anterior`/`status_novo` são
    texto livre lá, sem CHECK, e contam a história de quando o play existia
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
  **proximo_vencimento** (V17, 22/09/2026) é a data da próxima conta, vista na mesma consulta
  que constatou a quitação. Resolve o problema que a equipe levantou: o cliente pode estar
  quitado hoje e ter conta vencendo amanhã, e o projeto encaminhado nessa véspera volta
  reprovado — quando a Coelba for analisar, já existe débito. Faltando **um dia ou menos**,
  `/encaminhar` e `/reencaminhar` recusam com 409 `PROXIMO_DEBITO_A_VENCER`.
  <p>
  Opcional de propósito: "não informado" é o caso normal e é diferente de "não existe próxima
  conta" — inventar uma data faria o envio ser recusado por um vencimento que ninguém viu. Só
  vale com `QUITADO`: com débito ATIVO não há "próxima" a esperar, há a atual, e é ela que já
  barra o envio; guardar as duas daria dois códigos de erro para o mesmo cliente parado pelo
  mesmo motivo. Reconsultar **substitui** a data, que é como se descobre que a conta mudou de
  vencimento ou que não há mais nenhuma à vista.
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
  - **numero_solicitacao**: o número que a Coelba devolve ao receber o projeto. É a chave que
    casa o e-mail diário de status com o registro daqui (seção 9) — sem ela a automação teria de
    casar por nome de cliente, que é ambíguo. `q` da listagem busca por ele além de nome e UC.
    Sem UNIQUE: reenvio pode receber outro número, e a planilha traz o campo irregular
    - ⚠️ **Obrigatório no `/encaminhar` e no `/reencaminhar`** (decisão do usuário em
      17/09/2026). Era opcional, sob o argumento de que o retorno da Coelba às vezes demora; o
      que isso produzia era projeto enviado **sem chave de volta**, que o e-mail nunca alcança —
      a automação da etapa 3 cairia em `SEM_CORRESPONDENCIA` sem ninguém entender por quê
    - Obrigatório **também no reenvio**, e não só no primeiro envio: é ali que a Coelba pode
      devolver outro número, e aceitar vazio manteria o do ciclo anterior. Pior que recusar,
      porque o e-mail novo casaria com um número velho. A tela pré-preenche o número atual, então
      o custo é confirmar
    - A coluna **segue nulável**: projeto em `RECEBIDO` ainda não foi à Coelba, e a importação da
      planilha traz o campo irregular. A obrigatoriedade é da ação de enviar, não do campo
    - O `PUT /api/projetos/{id}` **corrige mas não apaga** o número, ao contrário dos outros
      campos que ele substitui. Sem essa exceção a regra seria contornável por uma edição, e um
      projeto já enviado ficaria sem a chave do e-mail em silêncio
    - `POST /api/projetos/{id}/corrigir-status` (só ADMIN) continua passando por cima, de
      propósito: é a válvula da importação, onde os dados chegam fora de ordem
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

⚠️ **A exceção é o cliente `somente_pendencia`**, e a guarda vive em
`ProjetoService.criarOuAtivarProjetoParaCliente` (devolve `null`), não nos listeners. É o único
caminho automático de criação de projeto, então a guarda ali cobre os dois listeners de uma vez —
o da pendência resolvida e o da triagem sem pendência — e cobriria um terceiro que aparecesse
depois. `ProjetoService.criar` (a criação à mão) recusa com 409 `CLIENTE_SOMENTE_PENDENCIA`, senão
o botão "Novo Projeto" contornaria o fluxo curto sem ninguém notar.

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
| `email_coelba` (seção 9) | ninguém via API — não tem tela, a consulta é no banco | **ninguém via API** — escrito exclusivamente pelo job do Gmail | ninguém |
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
- **Conta de sistema não é gerenciável por ninguém**, administrador incluído
  (`usuario.conta_sistema`, V19, 28/09/2026). Achado A-01 da auditoria de segurança
  (`docs/security-audit`): a conta de integração não tem papel, então a guarda acima a deixava
  passar — um GESTOR a reativava, dava senha e papel e entrava por ela, e tudo o que fizesse
  ficava no histórico como "Integração automática". Agora ativar, definir senha, editar e
  excluir recusam com 403; login e renovação recusam mesmo com senha gravada no banco; e ela sai
  do `GET /api/usuarios`. Continua no `/lookup?ativo=false`, que é onde a tela resolve o nome do
  autor de uma linha do histórico.

**A gestão de usuários deixa trilha** (V19): criação, ativação/desativação, senha redefinida,
e-mail e papéis alterados publicam `UsuarioAlteradoEvent`, gravado em `historico_status` com
`entidade_tipo = USUARIO` e o autor tirado do token. Consulta em `GET /api/usuarios/{id}/historico`
(`@GerenciaUsuarios`). Antes não havia rastro nenhum, e um GESTOR podia redefinir a senha de
outro e agir em nome dele sem que ninguém soubesse. As linhas `USUARIO` não entram em métrica:
toda consulta do dashboard filtra por `entidade_tipo`. Não é máquina de estados — `status_novo`
é o que aconteceu (`ATIVO`, `INATIVO`, `SENHA_DEFINIDA`, `EMAIL_ALTERADO`) ou a lista de papéis
resultante.

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
- **Jackson 3 recusa componente primitivo ausente em `record`**: ele liga
  `FAIL_ON_NULL_FOR_PRIMITIVES` por padrão, ao contrário do Jackson 2. Acrescentar um
  `boolean` a um request DTO faz **todo** corpo que omita aquela chave voltar 400 — inclusive os
  que já existem no frontend, nos testes e na importação. Custou 30 testes vermelhos ao pôr
  `somentePendencia`/`banco` no `ClienteRequest`. Use `Boolean` e normalize no construtor compacto
  do record.
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
  ├── common/referencia  (listas fechadas do cadastro; ver abaixo)
  ├── integracao         (entrada e saída automáticas; ver seção 9)
  │   ├── nectar    (polling de entrada, cliente HTTP, ingestão, sincronização de etapa)
  │   └── gmail     (cliente HTTP, parser do e-mail da Coelba, trilha email_coelba)
  └── common
      ├── config    (WebMvc, JpaAuditing, OpenAPI)
      ├── security  (UsuarioAutenticado + resolver, meta-anotações de RBAC)
      ├── web       (PaginaResponse, ApiExceptionHandler, MotivoRequest)
      ├── event     (EntidadeStatusEvent)
      └── exception
  ```
  O `integracao` não tem controller: são jobs agendados que chamam os services dos módulos, o
  mesmo caminho da tela. É o que a regra arquitetural da seção 3 comprava.

**Listas fechadas do cadastro** (`common/referencia`, 17/09/2026): os 417 municípios da Bahia e
os vendedores, em `src/main/resources/referencia/*.txt`, servidos por `GET /api/referencias`.
<p>
⚠️ **Viviam só no frontend** (`src/data/constantes.ts`) e passaram para o servidor porque a
importação do Nectar precisa delas aqui: cidade e vendedor que vêm do CRM são normalizados contra
o mesmo padrão que o formulário oferece, e manter as listas só no frontend significaria duas
cópias — exatamente o que esta seção já registra sobre regra de negócio duplicada entre os dois
repositórios. O arquivo do frontend **foi apagado**; ele agora lê do endpoint.
<p>
`Referencias.municipioCanonico` / `vendedorCanonico` comparam sem acento, sem caixa e sem espaço
sobrando, e devolvem **nulo** para o que não está na lista. Vendedor casa pelo primeiro nome,
porque é só ele que a lista tem e o CRM manda o nome completo. Arquivos de recurso e não
constantes em Java por causa dos 417 municípios; e não tabela no banco porque mudam por deploy,
não pela operação — os vendedores são o caso que mais tende a virar tabela, já que mudam a cada
contratação.
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

  ⚠️ **Ordem das recusas no envio**: o `@NotBlank` do `numeroSolicitacao` é validação de corpo e
  roda no controller, **antes** da guarda de débito, que é regra de negócio no service. Então um
  envio sem número responde 400 `VALIDACAO`, não 409 `DEBITO_NAO_CONSULTADO`, mesmo quando as
  duas coisas faltam. Importa para quem escrever teste: mandar `{}` para provar a guarda de
  débito passa a ser aprovado pelo motivo errado — o `DebitoBloqueiaEnvioHttpTest` tem uma
  constante `NUMERO_VALIDO` exatamente por isso.
- **Três recusas diferentes no envio à Coelba**, e os três códigos são distinguíveis de
  propósito porque cada um pede uma ação diferente de quem está na tela: 409
  `DEBITO_NAO_CONSULTADO` pede a consulta na agência virtual, 409 `CLIENTE_COM_DEBITO` pede a
  cobrança, e 409 `PROXIMO_DEBITO_A_VENCER` pede **esperar** — não há o que cobrar, a conta ainda
  nem venceu. Juntá-los mandaria a analista atrás da coisa errada.
- **409 `CLIENTE_SOMENTE_PENDENCIA`** ao criar projeto para cliente de fluxo curto, e 409
  `PRIORIDADE_SEM_INSTALACAO` ao pedir prioridade por instalação sem a data. As duas são
  validações cruzadas entre campos, que o Bean Validation não faz sem anotação de classe — e que
  precisam valer também para origens que não passam pelo controller.
- **Prioridade primeiro em toda listagem** (`common/web/PrioridadePrimeiro`): as seis listagens
  prefixam a ordenação do usuário com a flag do cliente. É **prefixo**, não substituição — dentro
  de cada grupo a coluna escolhida continua valendo, então clicar num cabeçalho reordena os
  prioritários entre si e os normais entre si, sem misturá-los. O frontend faz o mesmo em
  `useOrdenacao`, e as duas pontas precisam concordar: se divergissem, paginar embaralharia a
  ordem entre uma página e outra.
- **409 `NUMERO_SOLICITACAO_OBRIGATORIO`** existe além do 400 do `@NotBlank`: a guarda vive
  também no `ProjetoService`, para valer quando a origem não é a tela (a importação da planilha,
  ou uma integração futura). Mesma razão da guarda de débito estar no service.

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
  - **Selos ao lado do nome do cliente** (`components/common/SelosCliente`, 22/09/2026):
    `Prioridade` (âmbar, seta para cima), `Só pendência` (cinza) e as etiquetas `CRM` e `Banco`.
    Num arquivo só porque aparecem em seis módulos — e cada um só aparece quando diz algo que
    muda o trabalho de quem olha: selo em toda linha vira ruído e deixa de ser informação
  - **Prioridade âmbar com seta, não vermelho**: a leitura precisa ser "este subiu na fila", não
    "este tem um problema", que é o que a paleta de erro diria

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
| 11 | WAF / rate limiting | **Rate limit feito; WAF pendente** | `ControleDeTentativasDeLogin` (19/09/2026) — ver abaixo. Falta ligar a nuvem laranja do Cloudflare no registro da API, o que só pode ser feito depois de o primeiro certificado sair (seção 14) |
| 12 | HTTPS/TLS | **Escrito, falta rodar** | Caddy no `deploy/`, com emissão e renovação automáticas do Let's Encrypt e redirecionamento de HTTP embutido (seção 14). Fica *feito* quando a pilha subir num servidor de verdade |

**Resumo prático**: dos 12, os itens 4 e 7 não se aplicam no sentido original (são pensados pra
SaaS multi-cliente); o 5 é opcional; os outros 9 valem para o SolarSync.

## 9. Integrações externas

Objetivo declarado do projeto: sair da planilha 100% manual para um sistema com margem de
automação. As **duas integrações de entrada estão implementadas** (17/09/2026) e o desenho de
eventos da seção 3 foi o ponto de extensão delas — nenhuma exigiu refatoração do domínio.

| Integração | Direção | Estado |
|---|---|---|
| **Nectar (CRM)** — criar cliente quando negócio fecha | Entrada | **Feito**: job puxa a cada 10 min (`integracao/nectar`) |
| **Gmail** — ler o retorno diário da Coelba | Entrada | **Feito**: job lê a cada 15 min e aplica o status (`integracao/gmail`) |
| **Nectar (CRM)** — mover a etapa do cliente conforme o projeto anda | Saída | **Feito** (22/09/2026): `NectarEtapaListener` → `NectarEtapaService` → `NectarClient`. Zero mudança em `pendencia`/`projeto`/`vistoria` |

**Todas desligadas por padrão**, e é o padrão que importa: os jobs e listeners são beans
`@ConditionalOnProperty`, então sem `solarsync.nectar.ativo=true` / `solarsync.gmail.ativo=true` /
`solarsync.nectar.saida.ativo=true` não existe nada agendado — nem nos testes, nem no dev de quem
não está mexendo nisso. Nenhuma credencial no repositório (item 6 do checklist): ver
`.env.example`.

⚠️ **`solarsync.*.ativo=true` em `config/application.properties` vazaria para os testes.** Aquele
arquivo é lido também durante os testes e com precedência sobre o classpath (seção 6) — então
ligar uma integração ali criaria os jobs dentro de **toda** suíte, fazendo chamada de rede de
verdade: o job do Nectar varrendo o CRM da empresa e o do Gmail lendo a caixa real e aplicando
status em projetos do banco de teste. Por isso o `AbstractIntegrationTest` força as duas como
`false`, ao lado do `dados-de-exemplo` que já caía nessa. **Guardar o token ali é seguro; ligar o
`ativo` ali, não** — o `ativo` vai por variável de ambiente.

⚠️ E não é só o `ativo`: **`solarsync.gmail.somente-conferencia` vaza igual**, e `ativo=false`
não protege disto. Só o job e o cliente HTTP são condicionais; o `RetornoCoelbaService` existe
sempre, então o modo conferência ligado na configuração local fez **todo** teste que prova que o
e-mail muda o status do projeto receber `CONFERENCIA`. Custou quatro testes vermelhos, e por
isso o `AbstractIntegrationTest` agora força também essa. Regra geral: propriedade que muda
comportamento de service — e não só a existência de um bean — precisa de override lá.

Nenhuma das três adiciona endpoint. As de entrada são jobs que chamam os services do domínio —
o mesmo caminho da tela —, e a de saída é um listener sobre os eventos que esses services já
publicavam. É exatamente o que a regra arquitetural da seção 3 comprava: auditoria em
`historico_status` e máquina de estados valem igual, venha a mudança de um clique ou de um
e-mail; e a volta ao CRM acontece igual, venha a transição da tela, do e-mail ou de uma
importação. `@EnableScheduling` fica em `integracao/IntegracaoConfig`.

### Nectar: entrada de clientes

`NectarSincronizacaoJob` → `NectarClient` → `NectarIngestaoService` → `ClienteService`.

- **Puxa, não recebe webhook** (decisão do usuário em 17/09/2026). Funciona sem a API exposta na
  internet — o HTTPS ainda é item pendente (seção 8, item 12) — e sem depender de alguém
  configurar webhook no painel do Nectar. O preço é até 10 min de atraso, que não importa: a
  etapa seguinte é humana.
- **Cria só o Cliente**, em `AGUARDANDO_VERIFICACAO` — nunca o Projeto (decisão do usuário). O
  CRM não sabe se há pendência na Coelba, e quem sabe é a analista. Criando o projeto aqui, o
  cliente sairia da fila da triagem como se alguém já tivesse checado, que é exatamente a
  confusão entre "checado, não tem nada" e "ninguém olhou ainda" que o `StatusTriagem` desfaz.
  O Projeto nasce sozinho depois, pelos listeners que já existiam.
- **Só cria, nunca atualiza.** A analista corrige nome, cidade e telefone na triagem, e deixar o
  CRM sobrescrever isso a cada 10 min desfaria a correção. Depois da entrada, quem manda no
  cadastro é o SolarSync.
- **Idempotência por oportunidade**, não por pessoa (confirmado pelo usuário em 17/09/2026):
  `cliente.nectar_oportunidade_id` com índice único parcial (V13). Um cliente com vários negócios
  ("PROJETO 2" a "PROJETO 5" do mesmo JOSE NERI) vira **um cadastro por projeto** — cada
  oportunidade é uma usina distinta, com UC e pendência próprias, e a planilha substituída também
  tinha uma linha por projeto. Vai no cliente, e não numa tabela de controle, porque é um dado do
  cliente: responde "de onde veio este cadastro".
- **A procedência aparece na tela**: `ClienteResponse` traz `origem`
  (`MANUAL` | `CRM_NECTAR`, derivada de `nectarOportunidadeId != null`) e a listagem de Clientes
  mostra um selo "CRM" (pedido do usuário em 17/09/2026). Importa na triagem porque a confiança
  nos dados é diferente: no cliente do CRM, campo em branco significa "o CRM não sabia" e pede
  preenchimento; no manual, é esquecimento. Campo próprio em vez de o frontend deduzir do id — a
  regra fica num lugar só. Derivado e não coluna: uma coluna poderia divergir do id que a origina.
- **`dataFechamento` vira `dataPagamento`**: é o marco zero da métrica "tempo médio sem ninguém
  mexer no cliente" (seção 5), e o negócio fechar no CRM é o que o Nectar tem de mais próximo de
  "o financeiro validou o cliente", que é o gatilho real da etapa 1.
- A **UC da Coelba nasce nula**: ela é descoberta na própria checagem da etapa 1, por quem
  consulta a agência virtual — não vem do CRM.
#### O contrato real do Nectar (conferido em 17/09/2026)

`GET /oportunidades` em `https://app.nectarcrm.com.br/crm/api/1`, cabeçalho `Access-Token`,
paginação `page`/`displayLength` (máx. 200). Os campos de uma oportunidade **não são
documentados**; tudo abaixo saiu da resposta real da carteira da ConectSol, e `OportunidadeNectar`
é o único ponto do projeto que os conhece. Os testes usam esses dados reais, defeitos incluídos.

⚠️ **`pipeline=<nome do funil>` é o único filtro que a API respeita.** Foram testados
`etapa`, `etapaAtual`, `idEtapa`, `sequencia`, `funilVenda`, `idFunilVenda`, `funil` e
`etapaNome`: todos são **silenciosamente ignorados** e devolvem a carteira inteira — o pior modo
de falhar, um filtro que parece funcionar. Daí a consulta ser por funil e a etapa ser filtrada em
Java.

**As duas etapas de entrada** (confirmadas pelo usuário em 17/09/2026), padrão em
`NectarProperties`:

| Funil | Etapa | Ids | Banco? |
|---|---|---|---|
| `5- Financeiro` | `VALIDADO PELO FINANCEIRO` | funil 58569, etapa 288685 | não |
| `4- Nota Fiscal` | `ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR` | funil 58574, etapa 288704 | **sim** |

⚠️ **A etiqueta "Banco" sai da etapa de entrada, e é por isso que ela precisa virar dado do
cliente na hora da importação**: a oportunidade segue andando no CRM, então a etapa por onde ela
entrou não sobrevive. `EtapaDeEntrada.banco` liga `cliente.banco`, que decide uma coisa só — a
etapa para onde o projeto aprovado volta no Nectar. Dentro do SolarSync o fluxo é idêntico ao
normal.

A etapa é identificada por **funil + nome**, nunca por um só. O número da etapa é a sequência
*dentro* do funil (a etapa 4 existe nos treze funis), e o nome também se repete: "VALIDADO PELO
FINANCEIRO" aparece no `5- Financeiro` e, como "FUNCIONANDO | VALIDADO PELO FINANCEIRO", no
`7- Instalação` — onde o cliente está instalado e funcionando, não entrando no fluxo. Os dois
nomes são comparados sem acento, sem caixa e sem espaço sobrando, porque são digitados no painel
e o custo de errar é um cliente que nunca aparece na fila da triagem, sem erro em lugar algum.
Volume atual: 13 oportunidades nas duas etapas, contra 308 no funil 5 e 23 no funil 4 — três
requisições por execução.

Três armadilhas do formato, todas com teste nomeando o caso:

- `id` é **número** e `etapa` é o **número da sequência**, não um objeto. O nome da etapa vem em
  `etapaNome`. O mapeamento que eu havia escrito por suposição quebrava a desserialização aqui.
- ⚠️ **`dataCriacao` vem corrompida** numa boa parte da base: anos `0024`, `0026`, `0028` (dia e
  mês trocados na origem). Não é lida em lugar nenhum — usá-la faria a métrica de tempo parado
  render dois mil anos. `stageEntryDate` é confiável.
- **Não existe campo de cidade** na oportunidade, no cliente nem nos campos personalizados.

**`dataPagamento`** vem do campo personalizado **"Data do Pagamento"** (`camposPersonalizados`,
mapa indexado pelo rótulo, valor em `dd/MM/yyyy`) — é literalmente o dado que se procura. O
rótulo é configurável (`solarsync.nectar.campo-data-pagamento`).

⚠️ **É o único dado de pagamento que a API expõe** (conferido em 17/09/2026, a pedido do
usuário): das 20 definições de campo personalizado do Nectar, só essa fala de pagamento, e
`/propostas` não tem nenhum campo de valor de entrada, parcela ou condição. As condições de
pagamento existem só como texto solto no título da oportunidade ("R$ 1000 + 15X R$ 453,34").
Preenchido em **14 das 20** oportunidades nas etapas de entrada; sem ele, usa-se
`stageEntryDate`, que para a etapa "VALIDADO PELO FINANCEIRO" é exatamente quando o financeiro
validou. Nas 6 que caíram no plano B a data saiu `21/05/2026` — quando a carteira foi importada
em massa para o Nectar, não um pagamento real. O plano B continua certo para negócio novo, e
errado para esses legados.

**O nome do cliente e a cidade saem do nome da oportunidade**, por convenção da equipe
(`CLIENTE_CIDADE_VENDEDOR_VALOR_TENSÃO`, com prefixo opcional entre parênteses marcando o tipo
do caso — `(Ampliação)`, `(PROJETO 3)`):

```
HUDSON OLIVEIRA SOUZA_VITÓRIA DA CONQUISTA_RODRIGO_3X + R$ 666,67 + 48X + R$ 526,40_380/220V
```

- **Nome**: o campo de nome do cliente no Nectar **frequentemente traz o título inteiro** — 3 de
  6 na segunda importação real. Por isso o nome também passa pelo primeiro trecho da convenção.
  A guarda é exigir **três ou mais** trechos: nome de pessoa não tem isso, e assim um nome
  legítimo com um sublinhado solto não é cortado.
- **Cidade**: segundo trecho. A guarda é "o trecho tem uma palavra de três letras ou mais" — uma
  regra só, em vez de uma lista de formatos a barrar, porque a lista sempre esquece um: foi assim
  que `380/220V` passou na primeira versão.

**Cidade e vendedor saem no padrão do cadastro, não como o CRM os escreveu** (pedido do usuário
em 17/09/2026). A primeira importação real produziu "CACULE", "VITÓRIA DA CONQUISTA",
"Vitória Da Conquista" e "Brumado" como cidades diferentes, e vendedores como "Rodrigo soares" e
"Deilson Abrantes" fora da lista de seleção da tela. Agora passam por `Referencias` (ver seção
6): cidade casa com os 417 municípios da Bahia, vendedor casa pelo **primeiro nome** (a lista tem
só ele), ambos ignorando caixa e acento.

⚠️ **O que não casa entra nulo**, não como texto livre — é o que preserva o padrão. Vale
principalmente para vendedor: o campo "responsável" do Nectar carrega gente do administrativo
além dos vendedores (a importação trouxe "Thainara Gomes", "Evelin Barros", "Evelyn Natyelle"),
e adivinhar que são vendedoras poluiria o campo. O job loga um WARN por valor não reconhecido —
foi assim que apareceu "Zenildo", vendedor que não está na lista.

### Gmail: retorno da Coelba

`RetornoCoelbaJob` → `GmailClient` → `ParserEmailCoelba` → `RetornoCoelbaService` →
`ProjetoService.aprovar/reprovar`.

- **Aplica o status automaticamente** (decisão do usuário em 17/09/2026, contra a alternativa de
  uma fila de confirmação). A ressalva registrada na hora: e-mail mal interpretado reprova um
  projeto de verdade e suja o `historico_status`, que sustenta o dashboard. O que compensa isso
  é o parser recusar palpite e a tabela `email_coelba` guardar tudo (abaixo).
- Autenticação por **refresh token** de uma conta OAuth comum, não por conta de serviço com
  delegação de domínio: a delegação exige configuração no console do Workspace pelo
  administrador do domínio, e aqui basta autorizar uma vez a caixa que já recebe o e-mail.
  Escopo `gmail.readonly` — **o job nunca escreve na caixa**.
- Escrito sobre o `RestClient` do Spring, sem as bibliotecas cliente do Google: são duas
  chamadas GET e uma de token, e o `google-api-services-gmail` traria toda a pilha HTTP e de
  JSON do Google para conviver com o Jackson 3 do Boot 4.
- **Casa pelo `numero_solicitacao`, nunca por nome de cliente** (é para isso que o campo existe,
  V12). O parser extrai *candidatos* generosamente — todos os números do texto, com preferência
  pelos que estão perto de "solicitação"/"protocolo" — e quem confirma qual é o número de
  verdade é o casamento com um projeto existente. Errar o formato faria a integração perder
  e-mail; ser generoso só produz candidato que não casa com nada.
- Entre projetos com o mesmo número (o reenvio pode receber outro, e `numero_solicitacao` não é
  único), os em `ENCAMINHADO`/`REENCAMINHADO` têm precedência: o retorno é sobre o ciclo
  pendente. Sobrando mais de um, **não aplica** — escolher no escuro é pior que não agir.
- A data de aprovação é a **data do e-mail**, no fuso `America/Bahia`, e não a da execução do
  job — mesma razão pela qual o débito registra a data da consulta e não a da digitação (seção
  5): senão a métrica de tempo até aprovação mediria a agilidade do job. Um e-mail das 20h30 em
  Salvador é 23h30 UTC e viraria o dia seguinte sem o fuso.

#### O e-mail real, conferido em 19/09/2026

O usuário forneceu três e-mails de verdade, e eles **desmentiram o desenho anterior do parser**.
Estão colados como chegam em `EmailRealDaNeoenergiaTest` — é esse arquivo, e não este texto, que
diz o que foi observado em vez de suposto.

Remetente: **`noreplyportalgd@neoenergia.com`**, assunto
`Portal da Geração Distribuída: Solicitação <número>`. A caixa que os recebe é
**`projetos.conectsolparatodos@gmail.com`** (Gmail comum, não Workspace — o que decide o tipo de
cliente OAuth; ver seção 10).

⚠️ **A consulta padrão não casava com nenhum deles.** Era
`from:(coelba.com.br OR neoenergia.com.br)`, e o domínio real é `neoenergia.com` — sem o `.br`.
A integração ligada teria lido uma caixa vazia todo dia, sem erro em lugar nenhum. É o modo de
falhar mais caro possível, e só apareceu porque os e-mails foram conferidos antes de ligar.

⚠️ **O resultado não está em adjetivo nenhum.** O e-mail é uma notificação de mudança de etapa:

```
Sua solicitação de acesso ... passou para uma nova etapa. Informamos que as informações e
documentação foram recebidas e serão avaliadas pela distribuidora.
Número da solicitação: 2608198955
Etapa anterior: Em Análise Técnica
Etapa atual: Aguardando solicitação de vistoria e Conexão
```

Nenhuma das palavras que o parser procurava (`deferid`, `indeferid`, `homologad`, `aprovad`)
aparece. Pior: **aquele parágrafo em prosa é idêntico, palavra por palavra, no e-mail que
confirma o envio e no que anuncia a aprovação**. Só a linha `Etapa atual` distingue os dois.

E a armadilha que teria custado caro: no e-mail de aprovação, a **etapa anterior** é "Em Análise
Técnica". Um parser lendo termos no texto inteiro devolveria "em análise" — a aprovação seria
descartada em silêncio e o projeto ficaria parado em `ENCAMINHADO` para sempre, com a Coelba
tendo aprovado.

Daí o parser ter **dois caminhos, nesta ordem**:

1. **A linha `Etapa atual:`**, quando existe, e mais nada do texto. Etapa desconhecida vira
   `NAO_RECONHECIDO` e **não** cai no caminho 2 — naquele corpo, procurar termo solto é ler o
   texto que não diz nada. As etapas são configuração (`solarsync.coelba.etapas-*`):
   | Etapa atual | Resultado |
   |---|---|
   | `Aguardando solicitação de vistoria e Conexão` | **APROVADO** — confirmado pelo usuário em 19/09/2026: é essa a notificação que, para a ConectSol, significa projeto aprovado. E é literalmente o ponto em que o SolarSync manda o projeto para a fila da Vistoria |
   | `Em Análise Técnica`, `Aguardando Documentação` | acompanhamento, nada muda |
2. **Busca por termos**, só quando não há `Etapa atual`. É o caso do **cancelamento**, que não
   anuncia etapa: "Sua solicitação ... foi cancelada", com `Motivo do cancelamento:` em linha
   própria. O motivo sai dessa linha rotulada e não do recorte em volta do termo — "cancelada"
   aparece na saudação, então o recorte genérico traria cabeçalho e link junto.

Cancelamento na Coelba = `REPROVADO` no SolarSync, confirmado pelo usuário ao rotular o e-mail
como "reprova".

#### O fluxo real do portal, e a etapa 4 automatizada

Reconstruído em 19/09/2026 a partir de 120 e-mails reais, usando o par `Etapa anterior → Etapa
atual` de cada um como aresta. **São ~30 e-mails por dia** (5347 em 180 dias), o que sozinho
mudou dois dimensionamentos: o teto por execução subiu de 50 para 300, e a ideia de varrer 90
dias no ensaio era inviável.

| `Etapa atual` | Frequência | O que faz no SolarSync |
|---|---|---|
| *(prosa, sem linha de etapa)* "passará para a etapa de estudos" | 20% | nada — acompanhamento |
| `Em Análise Técnica` | 12% | nada |
| `Aguardando solicitação de vistoria e Conexão` | 14% | **Projeto → APROVADO** |
| `Realizando vistoria e Conexão` | 33% | **Vistoria → SOLICITADA** |
| `Ponto de Conexão Aprovado` | 19% | **Vistoria → APROVADA** |
| `Solicitação Concluída` | raro | nada (decisão do usuário) |

⚠️ **`Etapa anterior` é lixo, e o portal manda notificações fora de ordem.** Dois e-mails da mesma
solicitação chegaram com **quatro segundos** de diferença, um deles com `Data limite` já vencida,
e um trazia `Etapa anterior: Solicitação Concluída` — etapa que nunca foi atual de nada. Quem
desempatou a ordem real foi a `Data limite` de cada um. Duas consequências no código:

- só `Etapa atual` é lida, nunca `Etapa anterior`;
- o job processa **do mais antigo para o mais novo**, invertendo a ordem do Gmail. Na ordem
  original, um lote atrasado terminaria aplicando o status do e-mail mais velho por último.

**A vistoria nunca é criada pela integração** (decisão do usuário): criar exige a data de
instalação, que é evento de campo e não existe no portal. O e-mail só avança uma vistoria que já
existe; sem ela, fica `SEM_CORRESPONDENCIA`. O preço assumido é que, se ninguém registrou a
instalação, aquele retorno não é aplicado — e não volta, porque o e-mail já contará como
processado.

#### Buscar só os projetos que esperam retorno

`solarsync.gmail.somente-projetos-conhecidos=true` (pedido do usuário em 19/09/2026, **para
produção**) recorta a busca aos e-mails que citam o `numero_solicitacao` de um projeto que ainda
espera notícia — `ProjetoRepository.numerosAguardandoRetornoDaCoelba()`, em lotes de 50 números
por consulta porque a busca do Gmail tem limite de tamanho.

⚠️ **São duas esperas, não uma.** Encaminhado/reencaminhado cobre a homologação; a vistoria em
aberto cobre a etapa 4, que chega com o projeto já em `APROVADO`. Recortar só pelos encaminhados
desligaria a automação da vistoria **em silêncio** — o e-mail simplesmente deixaria de ser
buscado, e nenhum teste de parser acusaria. É o que o `NumerosAguardandoRetornoTest` protege.

Fica **desligado por padrão**: num banco sem os projetos da operação a lista sai vazia e o job
não busca nada, o que é o comportamento certo mas inútil no ensaio — e é varrendo a caixa
inteira que se descobre formato novo.

⚠️ **O parser não adivinha** (`ParserEmailCoelba`), e é isso que torna o automático aceitável:

- afirmação dupla no mesmo e-mail (aprovação **e** reprovação) não escolhe uma: registra
  `AMBIGUO` e o projeto fica como está;
- **negação é tratada**: "não deferida" é reprovação, não aprovação. Sem isso, a negação de um
  termo de aprovação seria lida como aprovação — o erro mais caro possível aqui;
- os termos casam com **fronteira de palavra no começo** e sufixo livre no fim. As duas metades
  importam: o sufixo livre é o que permite configurar o radical (`deferid` casa "deferida" e
  "deferido"), e a fronteira é o que impede `deferid` de casar **dentro** de "indeferido" — sem
  ela todo indeferimento cairia como ambíguo, e a integração nunca aplicaria uma reprovação
  anunciada com a redação mais provável da Coelba. Custou um bug encontrado na revisão dos
  casos de teste;
- os termos **e as etapas** são configuração (`solarsync.coelba.termos-*` e `.etapas-*`),
  comparados sem acento e sem caixa. A Neoenergia muda a redação sem avisar, e ajustar uma
  propriedade é mais rápido que um deploy — vale principalmente para uma etapa nova, que é o
  que mais tende a aparecer.

#### Modo conferência (19/09/2026)

`solarsync.gmail.somente-conferencia=true` é o ensaio antes da estreia: o job lê os e-mails de
verdade, roda o parser, casa com o projeto, grava em `email_coelba` **o que teria feito** — e
não muda status nenhum. É o único desvio no caminho, e fica no fim de propósito: tudo antes
dele (parser, extração do número, escolha do projeto, motivo da reprova) é exatamente o que
roda no modo normal, senão o ensaio provaria um caminho diferente do que vai ao ar.

Existe porque a instrução anterior — "antes de ligar, processar alguns e-mails reais e conferir
`email_coelba.resultado`" — era contraditória: *processar* é aplicar, então a conferência só
acontecia depois de o primeiro palpite errado já ter reprovado um projeto de verdade e sujado o
`historico_status` que sustenta o dashboard. Decisão do usuário em 19/09/2026.

Dois detalhes que fazem o ensaio funcionar, e sem os quais ele seria pior que não existir:

- **`CONFERENCIA` não conta como processado** (`EmailCoelbaRepository.idsJaProcessados`).
  Desligado o modo, esses e-mails voltam a ser lidos e enfim aplicados. Fosse o contrário, o
  ensaio consumiria em silêncio justamente os e-mails que importavam — e a integração estrearia
  já tendo perdido a semana conferida.
- **Reler atualiza o registro em vez de duplicar**, e no modo conferência o job não descarta
  mensagem conhecida. É o que fecha o ciclo de ajustar os termos em `solarsync.coelba`,
  reiniciar e ver o novo veredito sobre os mesmos e-mails, sem depender de a Coelba mandar um
  novo. Custa uma chamada HTTP por mensagem por execução; o modo é temporário.

O log é **WARN** a cada execução, e não INFO: modo conferência esquecido ligado é a integração
parecendo funcionar sem nunca mudar um projeto.

**Tabela `email_coelba`** (V13) — duas funções, e a primeira é o que a obriga a existir:

1. **Idempotência**: o job lista por consulta (`newer_than:7d`), então as mesmas mensagens voltam
   na execução seguinte. O único em `mensagem_id` é o que impede reaplicar o status. A
   alternativa — rotular ou marcar como lida no Gmail — exigiria escopo de escrita na caixa.
2. **Auditoria**: o status muda sozinho e **não há tela** (decisão do usuário), então tem de
   haver onde olhar para responder "por que este projeto foi reprovado ontem às 8h". Guarda
   inclusive o e-mail que o parser **não** entendeu — o caso que não pode desaparecer em
   silêncio. O `resultado` tem oito valores (`APLICADO`, `CONFERENCIA`, `SEM_ALTERACAO`,
   `NAO_RECONHECIDO`, `SEM_CORRESPONDENCIA`, `AMBIGUO`, `TRANSICAO_INVALIDA`, `ERRO`) porque
   cada um aponta para uma causa e uma correção diferentes. A V15 abriu o CHECK para
   `CONFERENCIA`.

Como `historico_status`, **não tem endpoint de escrita** — só o job escreve. E, como ele, o
`projeto_id` **não tem FK**: com FK, excluir um projeto passaria a falhar com 409 por causa da
trilha da integração.

⚠️ **`RetornoCoelbaService` não é `@Transactional`, de propósito.** Cada passo abre a sua
transação: a mudança do projeto dentro do `ProjetoService`, e o registro em `email_coelba`
depois. Se fossem a mesma, uma transição barrada pela máquina de estados marcaria a transação
como "somente rollback" e o registro do que aconteceu — justamente o que se quer guardar — iria
embora junto. Há teste para esse caso (`transicaoBarradaPelaMaquinaDeEstadosNaoMudaNadaMasFicaRegistrada`).

**Conta de integração**: as mudanças automáticas são atribuídas a `integracao@conectsol.com`
(semeada pela V13, `ativo = false`, sem senha e sem papel), para a linha do tempo do projeto
dizer "Integração automática" em vez de deixar o autor em branco — indistinguível de um registro
importado da planilha. `ativo = false` é a proteção: não entra pelo login, não renova token e não
aparece em `/api/usuarios/lookup?ativo=true`, então ninguém atribui trabalho a ela por engano.
Os jobs chamam os services direto, sem passar por `@PreAuthorize`, então papel nenhum é
necessário.
<p>
⚠️ `ativo = false` **não bastava**: um GESTOR a reativava pela tela (achado A-01 da auditoria).
Desde a V19 a proteção de verdade é `conta_sistema = true` — ver seção 4.

**Falha nunca encerra o agendamento.** Cada job trata a exceção de rede, e cada item (uma
oportunidade, um e-mail) é tratado à parte — o Nectar fora do ar, um token revogado ou uma
linha problemática no CRM custam uma execução, não a integração.

### Nectar: a volta — a etapa do CRM segue o status do SolarSync (22/09/2026)

`NectarEtapaListener` (`@TransactionalEventListener(AFTER_COMMIT)`) → `NectarEtapaService` →
`NectarClient`.

É a integração de saída que faltava, e a que o desenho de eventos da seção 3 deixou mais barata:
**nada em `pendencia`, `projeto` ou `vistoria` mudou**. O efeito colateral bom é que uma origem
nova de mudança de status — a leitura do e-mail da Coelba, por exemplo — já cai aqui de graça.

O endpoint que faltava quando a entrada foi escrita: **`GET /pipelines`** devolve os treze funis
com todas as suas etapas (id, nome, sequência). Foi dele que saíram os ids abaixo, conferidos
contra a API real em 22/09/2026, e é dele que o cliente HTTP copia os objetos de funil e etapa em
vez de montá-los à mão.

#### O mapa de etapas

Só uma linha difere entre o fluxo normal e o Banco — a da aprovação —, e por isso a configuração
é um mapa geral mais um **mapa de diferenças** (`etapas-banco`) em vez de duas tabelas completas:
duas tabelas iguais em dez das onze linhas divergem no dia em que alguém mexe só numa.

| Situação no SolarSync | Etapa no Nectar | Normal | Banco |
|---|---|---|---|
| Pendência aberta | `6- Projetos` PENDÊNCIA CONTA COELBA OU ALTERAÇÃO NO PADRÃO | 288714 | = |
| Pendência resolvida / aguardando envio | `6- Projetos` PROJETO PARA FAZER | 288715 | = |
| Projeto encaminhado | `6- Projetos` PROJETO ENCAMINHADO | 288716 | = |
| Projeto reprovado | `6- Projetos` PROJETO REPROVADO | 288717 | = |
| Retificado e encaminhado de novo | `6- Projetos` PROJETO RETIFICADO E ENCAMINHADO NOVAMENTE | 288718 | = |
| **Projeto aprovado** | normal: AGUARDANDO INSTALAÇÃO · Banco: APROVADO SEM PAGAMENTO | 288719 | **288686** |
| Vistoria solicitada | `7- Instalação` VISTORIA SOLICITADA | 288728 | = |
| Vistoria reprovada | `7- Instalação` VISTORIA REPROVADA | 288725 | = |
| Vistoria resolicitada após reprova | `7- Instalação` CORREÇÃO DE ERRO NA OBRA E VISTORIA SOLICITADA NOVAMENTE | 288722 | = |
| Vistoria aprovada | `7- Instalação` VISTORIA APROVADA - 100% CONCLUIDO | 288726 | = |

⚠️ **O funil 7 tem duas etapas de vistoria aprovada.** A escolhida é a 288726, por decisão do
usuário em 22/09/2026 — a 288720 ("APROVADA - PENDENTE INSTALAÇÃO") ficou de fora.

`EtapaDoFluxo` **não é uma cópia dos status do domínio**, e é de propósito: o CRM acompanha a
gestão do cliente, não a máquina de estados da homologação. `RECEBIDO` e `AGUARDANDO_ENVIO` são
coisas diferentes aqui dentro e a mesma lá fora; já `SOLICITADA` na vistoria vira duas, porque o
Nectar distingue a primeira solicitação da que vem depois de uma reprova — e essa distinção só
existe olhando o status **anterior** do evento, já que a vistoria reaproveita o mesmo registro.

⚠️ **Resolver a pendência não move ninguém.** Quem move o cliente para "projeto para fazer" é o
projeto que nasce em seguida; mandar as duas movimentações seria duas chamadas ao CRM para o mesmo
instante do fluxo, com a segunda desfazendo a primeira em ordem indeterminada.

#### Idempotência, falha e reprocessamento

**Duas camadas de idempotência, por razões diferentes:**

1. a trilha local (`nectar_etapa_sincronizacao`) — evita a viagem quando já pusemos o cliente
   naquela etapa. É economia;
2. o próprio CRM, consultado antes do `PUT` — vale quando alguém arrastou o card no painel ou
   quando o banco daqui foi recriado. Essa é a que é verdade.

A trilha olha só a **última** linha do cliente, não todas: voltar para uma etapa anterior é
movimento legítimo (projeto aprovado que a Coelba revisa e reprova), e uma busca por "já estivemos
nesta etapa alguma vez" deixaria o cliente preso na etapa mais recente para sempre.

**Falha nunca desfaz nada aqui dentro.** O listener é `AFTER_COMMIT`: o status já está gravado
quando o CRM é chamado. Se ele recusar, fica uma linha `ERRO` — a única pista de que o CRM ficou
para trás, porque não há tela. O `NectarEtapaReprocessamentoJob` retoma essas falhas a cada 30
min, e **só as que ainda são a última palavra sobre o cliente**: repetir uma falha antiga
empurraria o cliente de volta no CRM, e a integração passaria a mentir com a melhor das intenções.

**Tabela `nectar_etapa_sincronizacao`** (V18) — irmã de `email_coelba`, e pelas mesmas razões: a
mudança acontece sozinha e não há tela. Como ela, não tem endpoint de escrita e o `cliente_id`
**não tem FK** (com FK, excluir um cliente falharia com 409 por causa da trilha de uma
integração). Os seis resultados — `APLICADO`, `CONFERENCIA`, `JA_NA_ETAPA`, `SEM_OPORTUNIDADE`,
`SEM_MAPEAMENTO`, `ERRO` — apontam cada um para uma causa e uma correção diferentes.

#### Modo conferência, e por que ele é o padrão aqui

⚠️ `solarsync.nectar.saida.somente-conferencia` é **`true` por padrão**, ao contrário do `ativo`.
Mover a etapa é uma **escrita** no CRM da empresa, e o corpo do `PUT /oportunidades/{id}` não é
documentado — o que se sabe dele veio de `OPTIONS`, que responde
`allow: HEAD,DELETE,GET,OPTIONS,PUT`. Em conferência o job faz tudo (resolve o cliente, a etapa,
consulta o CRM) e grava o que teria feito, sem o `PUT`. Conferida a trilha com dados reais,
desligue.

Daí também as duas decisões defensivas do cliente HTTP:

- **lê, altera e devolve a oportunidade inteira**, em vez de mandar um corpo mínimo. Num `PUT` de
  contrato não documentado, mandar só o que interessa é apostar que o servidor faz merge — e se
  ele substituir, o negócio perde valor, responsável e campos personalizados de uma vez;
- **os objetos de funil e etapa são copiados de `/pipelines`**, com todos os campos que o Nectar
  põe neles. Montar `{"id": 288716}` e esperar que baste seria a mesma aposta um nível abaixo.

Conferido em 22/09/2026 contra o CRM real, em modo conferência: o cliente ADEILTON (oportunidade
29878256) estava na etapa 288714 e o SolarSync resolveu corretamente "PROJETO PARA FAZER" como
destino, sem enviar nada.

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

### Limite de tentativas no login (19/09/2026)

O `/api/auth/login` é público e chama BCrypt, que é caro **de propósito** — é o que torna um
vazamento de hashes difícil de explorar. Sem limite, essa mesma lentidão vira o ataque: algumas
centenas de requisições por segundo ocupam todas as threads do servidor com hash de senha e o
sistema inteiro para, **sem que ninguém tenha adivinhado senha nenhuma**. Em segundo lugar, o
limite encarece adivinhar a senha de uma conta específica.

`ControleDeTentativasDeLogin` conta falhas em **duas chaves**, com limites diferentes:

| Chave | Padrão | Por quê |
|---|---|---|
| e-mail | 10 / 15 min | protege a conta de quem troca de origem a cada tentativa |
| origem | 60 / 15 min | **folgado de propósito**: a ConectSol inteira sai por um IP só, e um limite apertado trancaria o escritório porque uma pessoa errou a senha |

- **Fica no controller, não no service** — ao contrário de todas as outras guardas do projeto. A
  exceção se justifica porque esta não é regra de negócio: ela se apoia no IP de quem chamou,
  que é informação de transporte e não existe fora do HTTP. Quem decide se a senha confere
  continua sendo só o `LoginSenhaService`.
- **Recusa antes de chamar o service.** Passar pelo BCrypt para só depois recusar gastaria
  exatamente o recurso que o limite existe para proteger.
- **Acerto zera o contador do e-mail e não o da origem**, e a assimetria é deliberada: quem
  acertou provou ser dono daquela conta, mas não provou nada sobre a origem — que pode ser um
  atacante varrendo contas e que acertou uma. Zerar ali devolveria a janela inteira a cada
  acerto.
- **429 com `Retry-After`**, não 401: a requisição nem chegou a ser avaliada, e dizer
  "credenciais inválidas" confundiria justamente quem errou a senha e tenta entender por que não
  entra. Código `LIMITE_DE_TENTATIVAS`.
- ⚠️ **`solarsync.login.confiar-em-proxy` só depois do Cloudflare.** Ligado, o IP vem de
  `X-Forwarded-For`; sem um proxy de verdade na frente, qualquer um forja o cabeçalho e troca de
  origem a cada requisição, e o limite por origem deixa de existir. Desligado atrás de um proxy,
  o IP é sempre o do proxy e o limite vira global. Os dois erros são silenciosos — daí a
  propriedade existir em vez de o código adivinhar.

**Em memória**, num mapa: o SolarSync roda numa instância só. Não sobrevive a restart e não é
compartilhado entre instâncias (seção 11). Redis ou tabela acrescentariam infraestrutura para um
sistema de uma empresa só, e o restart só devolve ao atacante a janela que ele teria em quinze
minutos.

**Revogação de acesso é `usuario.ativo = false`** (`POST /api/usuarios/{id}/desativar`), não
exclusão: o access token morre em ≤15 min e a renovação passa a ser negada, preservando a
auditoria. Não há logout no servidor (a API é stateless; o cliente descarta os tokens).

⚠️ `historico_status.usuario_id` tem FK para `usuario`: apagar um usuário que já aparece no
histórico falha com 409. Desative em vez de apagar — é o caminho previsto.

**Variáveis de ambiente** (ver `.env.example`; nenhuma tem valor no repositório):

| Variável | Efeito se ausente |
|---|---|
| `SOLARSYNC_JWT_SEGREDO` | em dev, segredo aleatório no boot + WARN — tokens não sobrevivem a restart. **No perfil `prod` o boot falha** (`solarsync.jwt.segredo-obrigatorio=true`, achado A-02 da auditoria) |
| `SOLARSYNC_ADMIN_SENHA_INICIAL` | admin da V3 segue sem senha e **ninguém consegue entrar**. Com menos de 12 caracteres o boot falha — mas só quando ela vai de fato ser aplicada: num banco que já tem senha o valor é ignorado e não barra nada |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | banco fica vazio (comportamento normal) |
| `SOLARSYNC_CORS_ORIGENS` | `http://localhost:5173` |
| `SOLARSYNC_NECTAR_ATIVO` / `_TOKEN` | integração com o CRM desligada: o job não existe e nenhum cliente entra sozinho (seção 9) |
| `SOLARSYNC_NECTAR_SAIDA_ATIVO` | a etapa do cliente no Nectar não acompanha o projeto. Exige `_ATIVO` ligado também: é o mesmo host e o mesmo token |
| `SOLARSYNC_NECTAR_SAIDA_SOMENTE_CONFERENCIA` | **verdadeiro** — o ensaio é o padrão, porque mover a etapa é escrita no CRM da empresa. Só desligue depois de conferir a trilha com dados reais |
| `SOLARSYNC_GMAIL_ATIVO` / `_CLIENT_ID` / `_CLIENT_SECRET` / `_REFRESH_TOKEN` | leitura do e-mail da Coelba desligada: o job não existe e o status do projeto só muda pela tela |
| `SOLARSYNC_GMAIL_SOMENTE_CONFERENCIA` | **falso** — o job aplica o status. Enquanto a redação real da Coelba não for conferida, esta é a variável que precisa estar em `true` (seção 9) |

⚠️ `solarsync.gmail.ativo=true` **sem as três credenciais** não impede o boot — loga um ERROR na
construção do cliente e falha em toda execução. Deliberado: derrubar a aplicação por causa de uma
integração auxiliar mal configurada deixaria o sistema inteiro fora do ar por um e-mail não lido.

**Dados de exemplo** (`SOLARSYNC_DADOS_DE_EXEMPLO=true`, só em dev): `exemplo/DadosDeExemplo`
semeia 26 clientes cobrindo todos os estados do fluxo — pendência aberta/em andamento/resolvida/
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
- **O limite de tentativas do login vive em memória** e some no restart — reiniciar a aplicação
  devolve ao atacante a janela inteira. Também não é compartilhado entre instâncias, então o dia
  em que houver duas o limite efetivo dobra. Aceito enquanto for uma instância só num VPS; a
  saída, se mudar, é Redis ou uma tabela, não um contador maior.
- ~~Mensagem de erro de login não distingue API fora do ar de senha errada~~ — **fechado em
  19/09/2026**: o `client.ts` do frontend converte a falha de `fetch` em `ApiError` com
  `codigo: SEM_CONEXAO`, e vale para todas as telas, não só a de login. Junto entrou o
  tratamento do 429 do limite de tentativas, com contagem regressiva a partir do `Retry-After`.
- **`email_coelba` não tem tela** (decisão do usuário em 17/09/2026: "nenhuma tela; só log do
  servidor"). É a mesma armadilha do item acima, invertida: os dados existem e ninguém tem como
  olhar sem acesso ao banco. Vale enquanto só o João Gabriel diagnostica; no dia em que a Nycole
  perguntar "por que este projeto foi reprovado", o caminho é uma listagem só-leitura de
  `email_coelba` filtrada por `resultado`. A tabela já foi desenhada para isso.
- **Ninguém é avisado quando a integração para de entender o e-mail.** Se a Coelba mudar a
  redação, tudo passa a cair em `NAO_RECONHECIDO` — corretamente, sem status errado — mas a
  única pista é um WARN por execução. Como a consequência é silenciosa (os projetos simplesmente
  param de ser aprovados sozinhos, como se o e-mail não tivesse chegado), isto pode durar dias.
  A saída é o item acima, ou um aviso quando a proporção de não reconhecidos passar de um limite.
- **Nome e cidade do cliente vêm de uma convenção de nome, não de campos.** Não existe campo de
  cidade na API do Nectar, e o campo de nome do cliente costuma trazer o título inteiro da
  oportunidade — os dois saem do padrão `CLIENTE_CIDADE_VENDEDOR_...` (seção 9). Valeu para todas
  as oportunidades conferidas, mas é convenção digitada por gente: quando o nome usa `_` para
  outra coisa, a segunda posição é uma palavra qualquer e não há como saber que não é cidade
  (`Proposta_Comercial_CONECTSOL` → cidade "Comercial"). Cidade errada não decide nada no fluxo —
  só aparece nas listagens — e a analista corrige na triagem, que agora mostra o selo "CRM"
  justamente para ela saber de onde o dado veio. A correção de raiz é no Nectar: padronizar o
  campo de nome do cliente elimina a heurística.
- **Um cliente do CRM com vários negócios vira vários clientes no SolarSync.** Confirmado pelo
  usuário como o comportamento desejado: JOSE NERI tem quatro oportunidades ("PROJETO 2" a
  "PROJETO 5") e entra quatro vezes. O efeito colateral é o mesmo nome repetido na lista de
  clientes, sem nada que distinga as quatro linhas além do id — quem precisar saber qual é qual
  tem de abrir o negócio no Nectar pelo `nectarOportunidadeId`.
- **Vendedor fora da lista entra vazio, e ninguém é avisado na tela.** A importação real deixou
  5 dos 20 clientes sem vendedor, porque o "responsável" no Nectar era do administrativo ou um
  vendedor que não está na lista ("Zenildo"). O único aviso é um WARN por execução. Acrescentar o
  vendedor em `src/main/resources/referencia/vendedores.txt` exige deploy — é o que mais pesa a
  favor de essa lista virar tabela.
- **Nenhum job é testado ligado.** `NectarClient`, `GmailClient` e os dois jobs são beans
  `@ConditionalOnProperty`: com `ativo=false`, que é o padrão e o que os testes forçam, esses
  beans **não existem** e nenhum teste os toca. Foi assim que a falta do bean
  `RestClient.Builder` passou por 174 testes verdes e só apareceu ao subir a aplicação com a
  integração ligada (seção 6). Depois de mexer nos clientes ou nos jobs, subir com
  `SOLARSYNC_NECTAR_ATIVO=true` é a única prova.
- **`email_coelba` fica órfã ao excluir projeto**, pela mesma razão e com o mesmo efeito de
  `historico_status`: sem FK em `projeto_id` de propósito, porque a FK faria `DELETE` de projeto
  falhar com 409 por causa da trilha da integração.
- **Cliente do CRM importado antes de 22/09/2026 não vem marcado como Banco.** A etiqueta sai da
  etapa de entrada da oportunidade, e essa etapa não sobrevive à importação — a oportunidade segue
  andando no CRM. A V16 dá `banco = false` a todos os clientes existentes, e não há como a
  migration deduzir quem veio da etapa de banco. Conferido na importação real: as 4 oportunidades
  do funil "4- Nota Fiscal" já estavam no banco e continuaram sem a marca. A correção é o
  checkbox no cadastro, cliente a cliente; a partir de agora os novos entram marcados sozinhos.
- **A sincronização de etapa move o cliente, e o CRM pode ter regra própria sobre isso.** O
  `PUT /oportunidades/{id}` não é documentado: pode haver etapa que exige proposta, campo
  obrigatório ou permissão do usuário do token. Nada disso apareceu no ensaio (que não escreve),
  e o primeiro `PUT` de verdade é onde se descobre. É o motivo de o modo conferência ser o padrão
  e de a trilha guardar a mensagem da recusa.
- **A integração só enxerga o retorno que chegou na caixa certa, e nem todo chega.** O e-mail de
  aprovação conferido em 19/09/2026 foi endereçado a `projetos.conectsolparaatodos` — com o "a"
  dobrado, erro de digitação do projetista ao cadastrar a solicitação no portal da Neoenergia. O
  endereço real é `projetos.conectsolparatodos@gmail.com`. Consequência: o projeto cujo cadastro
  no portal tem o endereço errado **nunca** terá o status aplicado sozinho, e não há como o
  sistema saber disso — não existe e-mail para não reconhecer. A correção é no portal, projeto a
  projeto; aqui o sintoma é projeto parado em `ENCAMINHADO` sem nada em `email_coelba`. Vale
  conferir os `ENCAMINHADO` antigos à mão de tempos em tempos, ou tratar o endereço da
  notificação como item de conferência ao enviar o projeto.

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
9. ~~Integrações de entrada da seção 9 (17/09/2026)~~ — **feito**: recebimento de clientes pelo
   Nectar (polling a cada 10 min, cria só o Cliente na fila da triagem, idempotente por
   `nectar_oportunidade_id`) e retorno de aprova/reprova da Coelba pelo Gmail (leitura a cada
   15 min, casamento pelo `numero_solicitacao`, aplicação automática do status com trilha em
   `email_coelba`). As duas desligadas por padrão. O `numero_solicitacao` do Projeto, criado
   pensando nisto na V12, foi de fato a chave do casamento
10. ~~Conferir o contrato do Nectar contra a API real~~ — **feito** (17/09/2026): mapeamento
    reescrito sobre a resposta de verdade, as duas etapas de entrada confirmadas, o filtro
    `pipeline` descoberto e a falta do bean `RestClient.Builder` corrigida. Detalhes na seção 9
11. ~~Pendência com um único status ativo (19/09/2026)~~ — **feito**: `EM_ANDAMENTO`, o endpoint
    `/iniciar` e o botão "play" da tela saíram; apontar a pendência na triagem já é iniciá-la
    (V14, seção 3). Veio do uso real: o clique parecia ligar o relógio e não ligava nada
12. ~~Modo conferência do Gmail (19/09/2026)~~ — **feito**:
    `solarsync.gmail.somente-conferencia` (V15, seção 9). Era a peça que faltava para o item
    abaixo ser seguro: sem ela, "conferir antes de ligar" só era possível depois de a
    integração já ter aplicado um status
13. ~~Conferir a redação real do e-mail da Coelba (19/09/2026)~~ — **feito**: três e-mails de
    verdade fornecidos pelo usuário, e o parser **reescrito** sobre eles. O desenho anterior
    (termos como `deferid`/`indeferid` no corpo) estava errado: o resultado vem da linha
    `Etapa atual`, e nenhuma daquelas palavras existe no e-mail. A consulta padrão do Gmail
    também não casava com o remetente. Detalhes na seção 9, e os e-mails colados em
    `EmailRealDaNeoenergiaTest`
14. ~~Ensaio do Gmail contra a caixa real (19/09/2026)~~ — **feito**: cliente OAuth criado
    (tipo "Aplicativo para computador", app Externo publicado — em modo Teste o refresh token
    morreria em 7 dias), `scripts/obter-refresh-token-gmail.ps1`, e duas execuções em modo
    conferência contra `projetos.conectsolparatodos@gmail.com`. **117 e-mails lidos, 107
    entendidos, zero `NAO_RECONHECIDO`, nenhum status alterado** — 55 acompanhamentos, 36
    vistorias solicitadas, 13 aprovações, 3 vistorias aprovadas. O que sobrou de
    `SEM_CORRESPONDENCIA` é o item 15
15. **Próximo, e o que trava a integração do Gmail valer alguma coisa**: não há projetos com
    `numero_solicitacao` no banco para o e-mail casar. O parser está certo e não tem o que
    aplicar. Os números saem da **importação da planilha** ou do uso real do sistema — até lá,
    todo e-mail cai em `SEM_CORRESPONDENCIA`, corretamente
16. **Importação da planilha** `PLANILHA_TESTE_-_PROJETOS_.xlsx` (usar
    `POST /api/projetos/{id}/corrigir-status` para os registros que chegam fora de ordem, e a
    data de consulta/solicitação nos módulos que aceitam data retroativa, para as métricas não
    nascerem zeradas, e registrar as consultas de débito dos dois tipos, que agora são exigidas
    para resolver pendência e encaminhar projeto). ⚠️ **Trazer o `numero_solicitacao`** de cada
    projeto já enviado: é a chave que liga o e-mail ao registro, e sem ela a integração do Gmail
    fica sem efeito para toda a carteira atual
17. **Ligar o Gmail para valer**, depois do item 16: conferir `email_coelba` com dados reais,
    `somente-conferencia=false` e `somente-projetos-conhecidos=true`
18. Antes de ir ao ar: rate limit no `/api/auth/login` (item 11 do checklist — sem ele o BCrypt
    é vetor de DoS), HTTPS/TLS (item 12) e a auditoria de segurança (item 10)
19. ~~Integração de **saída** para o Nectar~~ — **feita** (22/09/2026): a etapa do cliente no
    CRM passa a seguir o status do projeto, com mapa próprio para o fluxo Banco (seção 9). Como
    previsto, custou um listener e zero mudança em `pendencia`/`projeto`/`vistoria`. Está em modo
    conferência por padrão; **o próximo passo é conferir a trilha `nectar_etapa_sincronizacao`
    com dados reais e então desligar `somente-conferencia`** — é o primeiro `PUT` no CRM da
    empresa, e o corpo dele não é documentado
20. Listagem só-leitura de `email_coelba`, filtrada por `resultado` — hoje a trilha existe e
    ninguém tem como olhar sem acesso ao banco (seção 11). Vira urgente no dia em que alguém
    perguntar "por que este projeto foi reprovado ontem"
21. **Rodada de 22/09/2026 (apresentação para a equipe de Projetos)** — feita: prioridade de
    cliente com propagação da data de instalação para o projeto, fluxo curto "somente pendência",
    débito futuro barrando o envio na véspera, etiqueta Banco vinda da etapa de entrada do CRM, e
    a sincronização de saída acima. Migrations V16–V18
22. **Backfill da etiqueta Banco** nos clientes importados antes de 22/09/2026 — hoje só o
    checkbox no cadastro, cliente a cliente (seção 11)
23. **Deploy de homologação (24/09/2026)** — os artefatos estão escritos (`Dockerfile`,
    `deploy/`, seção 14); **falta o servidor**. O que trava: contratar o VPS e criar os dois
    registros de DNS em `conectsol.com`. Nada disso é código
24. **Ligar o Cloudflare na frente da API** (item 11 do checklist) — só depois de o primeiro
    certificado ser emitido, senão o desafio do Let's Encrypt não chega ao Caddy

## 14. Deploy

Escrito em 24/09/2026 para o ambiente de **homologação** — a equipe de Projetos testando antes
de a planilha ser substituída. O runbook passo a passo é o `deploy/README.md`; esta seção
registra só as decisões e o porquê delas.

```
  navegador ── https://solarsync.conectsol.com ──► VPS
                                                    ├── caddy  /      → estáticos da interface
                                                    │          /api/* → proxy para a api
                                                    ├── api      (:8080, rede interna)
                                                    └── postgres (:5432, rede interna)
```

**Um VPS com Docker Compose, e não Cloud Run**, apesar de o outro projeto da casa (o de
devoluções) usar `gcloud run deploy --source` com Supabase. O que decide é o trabalho agendado:
o job do Nectar a cada 10 min, o do Gmail a cada 15 e o reprocessamento da saída a cada 30. Em
Cloud Run com escala a zero esses jobs **não rodam** quando não há requisição, e não rodam em
silêncio — sem erro, sem log, os clientes só param de entrar. É exatamente a classe de falha que
a seção 11 já cataloga duas vezes (o `pipeline` que ignora filtro, o domínio `neoenergia.com.br`
que não casava com nada). Manter uma instância sempre ligada para contornar custa mais que o VPS
e ainda exige banco gerenciado à parte.

**Um domínio só, e é a decisão central do desenho.** A tela em `/` e a API em `/api/*` no mesmo
host significa requisição de **mesma origem**: sem CORS, sem preflight, e sem uma lista de origens
no servidor para alguém errar. Importa porque errar essa lista é o modo de falhar mais confuso que
este sistema tem — a tela carrega perfeitamente e nenhuma requisição funciona, e o erro aparece
só no console do navegador, porque do lado da API a requisição nem chega a ser processada.
<p>
⚠️ Isto **reverteu** o desenho anterior, em que o frontend ia para o Cloudflare Pages e a API para
`api.solarsync.conectsol.com`. O Pages dava CDN e build automático de graça, mas comprava
exatamente aquele risco de CORS — e o CDN quase não paga, porque só acelera o primeiro
carregamento: toda interação depois vai ao VPS de qualquer jeito. Para dez pessoas internas, um
domínio, um deploy e nenhuma conta de terceiro valem mais.
<p>
**Duas imagens, dois repositórios, e o Caddy sai do repositório do frontend.** A imagem do
`solarsync-web` é um Caddy com o `dist` do Vite dentro; a configuração dele (`deploy/Caddyfile`)
continua aqui, montada pelo compose. Cada repositório sabe construir a si mesmo, e o desenho da
pilha fica num lugar só. O preço é o compose precisar do clone do frontend **ao lado** deste
(`SOLARSYNC_FRONT_CAMINHO`), e o `deploy.sh` atualizar os dois — atualizar só um publicaria
backend novo com a tela velha, e nada acusaria, porque tudo sobe saudável.
<p>
Consequência no cliente HTTP: `BASE_URL` é **vazio** em produção (`src/api/client.ts`), e o
`new URL` ganhou `window.location.origin` como base — sem isso, caminho relativo estoura com
"Invalid URL". `VITE_API_URL` continua existindo para apontar para outro servidor, e continua
sendo lida no build; a diferença é que agora ninguém precisa dela.

**Caddy, e não nginx + certbot.** A renovação é a parte do par que mais dá trabalho manter, e um
certificado vencido num domingo derruba o sistema inteiro. O Caddy emite e renova sozinho; o
preço é uma imagem a mais que ninguém aqui conhece de cor.

Decisões que valem registro:

- **Content-Security-Policy no Caddy** (28/09/2026, achado A-04 da auditoria). Os tokens ficam em
  `localStorage`, e não há sink de XSS no código — a CSP é o que contém o estrago do dia em que
  houver um. `script-src 'self'` sem `unsafe-inline`/`unsafe-eval`; o estilo e a fonte vêm só do
  bundle e da Google Fonts; `connect-src 'self'`, que só funciona **porque** tela e API são o
  mesmo domínio. Conferido no navegador com o bundle de produção: a tela carrega sem violação, e
  um `<script>` inline injetado é recusado. ⚠️ Toda origem externa nova (CDN, analytics, um
  segundo domínio de API) precisa entrar no cabeçalho, senão quebra em silêncio — o erro só
  aparece no console do navegador.

- **Só o Caddy publica porta.** Postgres e API existem apenas na rede interna do compose. Expor
  5432 num IP público é como o banco de um sistema interno costuma vazar.
- **`SOLARSYNC_LOGIN_CONFIAR_EM_PROXY` é forçado a `true` no compose**, não deixado no `.env`.
  Nesta pilha sempre há um proxy de verdade na frente; com `false`, toda requisição pareceria vir
  do IP do container do Caddy e o limite de 60/15min por origem viraria global — o escritório
  inteiro trancado porque uma pessoa errou a senha. É um dos dois erros silenciosos que a
  seção 10 descreve, e a única forma de não cometê-lo é não deixar a escolha aberta.
- **As três integrações ficam desligadas em homologação.** O job do Nectar lê o CRM de verdade da
  empresa, o da saída **escreve** nele e o do Gmail lê a caixa real: ligados num ambiente de
  teste, a equipe treinaria em cima de dados de produção e o CRM começaria a se mexer sozinho por
  causa de um clique de ensaio.
- **O container da API tem teto de memória** (`mem_limit`, 1536m por padrão). Sem ele, o
  `-XX:MaxRAMPercentage=75` do Dockerfile é 75% da RAM do **servidor inteiro**, e a JVM passa a
  contar com a memória do Postgres também. O sintoma seria o Postgres morrer por OOM sob carga,
  longe da causa.
- **`/actuator/health` existe agora** (dependência `spring-boot-starter-actuator`, exposição
  restrita a `health` e sem detalhe). É o que o healthcheck do container consulta para o compose
  saber que a aplicação **subiu**, e não só que a porta abriu — o Flyway roda antes do primeiro
  OK. É `permitAll` no `SecurityConfig` porque o healthcheck roda sem token, e o Caddy responde
  404 nele de fora: de dentro do container é diagnóstico, de fora é a confirmação de que o
  sistema existe e de qual é o estado do banco.

⚠️ **A imagem não roda os testes.** A suíte exige Docker (Testcontainers) e rodá-la dentro do
build seria Docker dentro de Docker. `./mvnw test` é responsabilidade de quem publica, antes de
subir — o `deploy/deploy.sh` diz isso em comentário, mas não tem como impor.

⚠️ **O registro de DNS da API entra como "DNS only" (nuvem cinza) no Cloudflare.** Com o proxy
ligado antes do primeiro certificado, o desafio do Let's Encrypt não chega ao Caddy e a emissão
falha. O WAF do item 11 é ligar a nuvem laranja **depois**.
