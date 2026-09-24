-- Três atributos do cliente que vieram da apresentação para a equipe de Projetos (22/09/2026).
-- Ficam todos no cliente, e não em entidades próprias, porque os três acompanham a pessoa por
-- todas as etapas — é justamente isso que o requisito pede da prioridade ("independentemente da
-- etapa atual") e do fluxo curto ("o cliente não passa pelo fluxo normal").

-- ---------- Prioridade ----------
-- Pedido de adiantamento: instalação antecipada, prazo menor em contrato, prazo do cliente.
-- O efeito é de ordenação: o cliente prioritário sobe ao topo da fila da etapa em que estiver, e
-- continua subindo nas etapas seguintes até alguém encerrar a prioridade.
ALTER TABLE cliente
    ADD COLUMN prioridade BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN prioridade_motivo VARCHAR(30),
    ADD COLUMN prioridade_observacao VARCHAR(500),
    ADD COLUMN prioridade_definida_em TIMESTAMPTZ,
    ADD COLUMN prioridade_definida_por_id BIGINT REFERENCES usuario (id),
    -- A data em que o cliente foi instalado, quando a prioridade é por instalação adiantada.
    -- NÃO é um segundo campo de data de instalação: é onde ela fica enquanto o projeto do
    -- cliente ainda não existe (a prioridade é marcada na triagem, antes de haver projeto).
    -- Assim que há projeto, o valor é copiado para projeto.data_instalacao, que continua sendo
    -- o campo que a vistoria lê — ver ClienteService.marcarPrioridade e ProjetoService.
    ADD COLUMN prioridade_data_instalacao DATE;

ALTER TABLE cliente ADD CONSTRAINT ck_cliente_prioridade_motivo
    CHECK (prioridade_motivo IS NULL OR prioridade_motivo IN (
        'INSTALACAO_ADIANTADA', 'PRAZO_CONTRATUAL', 'PRAZO_DO_CLIENTE', 'OUTRO'));

-- Prioridade sem motivo não diz nada a quem vê a fila e não dá para revisar depois.
ALTER TABLE cliente ADD CONSTRAINT ck_cliente_prioridade_com_motivo
    CHECK (prioridade = FALSE OR prioridade_motivo IS NOT NULL);

-- A data de instalação é obrigatória só quando o motivo é a instalação — é a regra do requisito,
-- e mantê-la no banco impede que uma origem futura (importação, integração) grave o par
-- incompleto que a etapa de vistoria depois não consegue usar.
ALTER TABLE cliente ADD CONSTRAINT ck_cliente_prioridade_instalacao
    CHECK (prioridade_motivo IS DISTINCT FROM 'INSTALACAO_ADIANTADA'
           OR prioridade_data_instalacao IS NOT NULL);

-- Índice parcial: a consulta é sempre "quem está prioritário", nunca "quem não está", e os
-- prioritários são poucos por definição.
CREATE INDEX idx_cliente_prioridade ON cliente (prioridade) WHERE prioridade;

-- ---------- Fluxo somente pendência ----------
-- O gestor manda um cliente avulso ao setor só para resolver uma pendência específica. Resolvida
-- a pendência, o fluxo acabou: não nasce projeto, não há encaminhamento nem vistoria.
-- É flag e não um status do cliente porque não é uma etapa — é o desenho do fluxo dele, decidido
-- na entrada e válido do começo ao fim.
ALTER TABLE cliente
    ADD COLUMN somente_pendencia BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------- Cliente/projeto Banco ----------
-- O projeto pago por financiamento bancário segue o mesmo fluxo interno, mas ocupa outra etapa
-- no Nectar quando aprovado ("PROJETO APROVADO SEM PAGAMENTO (BANCO, NEGOCIAÇÃO, ETC)"), porque
-- lá o que se acompanha é o pagamento. Coluna própria, e não dedução da etapa de origem no CRM:
-- a etapa de origem não fica registrada em lugar nenhum depois da importação, e um cliente Banco
-- cadastrado à mão não teria como ser marcado.
ALTER TABLE cliente
    ADD COLUMN banco BOOLEAN NOT NULL DEFAULT FALSE;
