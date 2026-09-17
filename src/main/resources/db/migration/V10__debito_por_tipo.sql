-- V10: o débito da Coelba trava duas etapas diferentes, e a operação precisa distingui-las.
--
-- Um débito pode impedir a resolução da pendência (etapa 1: a Coelba não faz troca de
-- titularidade, ligação nova etc. com a UC devendo) e outro pode impedir a homologação do
-- projeto (etapa 2/3). São situações distintas, resolvidas por pessoas distintas em momentos
-- distintos — e um cliente com mais de uma UC pode ter as duas ao mesmo tempo.
--
-- Antes havia um registro por cliente travando as duas coisas, então não dava para responder
-- "este cliente está parado por quê, e há quanto tempo" — que é a pergunta do financeiro.

ALTER TABLE debito ADD COLUMN tipo VARCHAR(20);

UPDATE debito SET tipo = 'HOMOLOGACAO';

-- O registro antigo respondia às duas perguntas ao mesmo tempo. Duplicá-lo preserva exatamente
-- o comportamento atual; sem isso todo cliente já consultado reapareceria como "ninguém olhou"
-- na etapa de pendência, e a fila nasceria mentindo.
INSERT INTO debito (cliente_id, status, ultima_consulta_em, tipo, criado_em, atualizado_em)
SELECT cliente_id, status, ultima_consulta_em, 'PENDENCIA', criado_em, atualizado_em
  FROM debito;

ALTER TABLE debito ALTER COLUMN tipo SET NOT NULL;

ALTER TABLE debito ADD CONSTRAINT ck_debito_tipo CHECK (tipo IN ('PENDENCIA', 'HOMOLOGACAO'));

ALTER TABLE debito DROP CONSTRAINT uk_debito_cliente;
ALTER TABLE debito ADD CONSTRAINT uk_debito_cliente_tipo UNIQUE (cliente_id, tipo);

-- Relógio do tempo parado. O histórico continua sendo a fonte da média do dashboard, mas a
-- tela de débitos precisa de "parado há N dias" linha a linha, e varrer historico_status para
-- cada linha da listagem seria caro e frágil.
ALTER TABLE debito ADD COLUMN detectado_em TIMESTAMPTZ;
ALTER TABLE debito ADD COLUMN quitado_em TIMESTAMPTZ;

-- Quem consultou. Na prática o débito de homologação é consultado pelo projetista ao receber o
-- cliente, e o de pendência por quem trabalha a pendência — saber quem olhou fecha o rastro.
ALTER TABLE debito ADD COLUMN consultado_por_id BIGINT REFERENCES usuario (id);

UPDATE debito SET detectado_em = ultima_consulta_em WHERE status = 'ATIVO';

CREATE INDEX idx_debito_tipo_status ON debito (tipo, status);
