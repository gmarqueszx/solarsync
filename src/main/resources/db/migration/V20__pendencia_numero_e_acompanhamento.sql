-- V20: duas colunas novas na pendência, pedidas pela equipe de Projetos.
--
-- numero_solicitacao: alguns tipos de pendência (troca de titularidade, ligação nova...) geram
-- um número de solicitação na Coelba. Opcional, porque outros tipos não geram nenhum. Sem UNIQUE
-- pelo mesmo motivo do projeto (V12): reabrir e solicitar de novo pode trazer outro número.
--
-- status_acompanhamento: anotação de trabalho de quem acompanha a pendência ("stand by",
-- "período de leitura", "nota retida"...). NÃO é o status do ciclo de vida — esse continua
-- sendo `status` (ABERTA/RESOLVIDA/CANCELADA), com máquina de estados e auditoria. Este campo
-- não tem transição barrada nem evento: é etiqueta livre dentro de uma lista fechada, e nulo
-- significa "ninguém classificou ainda".

ALTER TABLE pendencia ADD COLUMN numero_solicitacao VARCHAR(50);

CREATE INDEX idx_pendencia_numero_solicitacao ON pendencia (numero_solicitacao);

ALTER TABLE pendencia ADD COLUMN status_acompanhamento VARCHAR(40);

ALTER TABLE pendencia ADD CONSTRAINT ck_pendencia_status_acompanhamento
    CHECK (status_acompanhamento IN (
        'PENDENTE',
        'DEBITO',
        'ACOMPANHAR',
        'PERIODO_DE_LEITURA',
        'AGUARDO_ACOMPANHAMENTO',
        'STAND_BY',
        'PROJETO_CANCELADO',
        'B_OPTANTE',
        'SOLICITACAO_CANCELAMENTO',
        'RESOLVER_NA_COELBA',
        'FOI_SEM_BENEFICIARIO',
        'NOTA_RETIDA',
        'OBRA',
        'PAGO_AG_DEBITAR'
    ));
