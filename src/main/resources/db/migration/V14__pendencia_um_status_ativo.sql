-- V14: a pendência passa a ter um único status ativo.
--
-- Apontar a pendência na triagem do cliente É iniciar a solicitação: dali o cliente já cai na
-- fila de Pendências e a solicitação já correu na Coelba. EM_ANDAMENTO exigia um clique de
-- "play" que não mudava nada — a métrica de tempo de resolução sempre saiu de
-- (resolvido_em - solicitado_em), e solicitado_em é gravado na criação. O status a mais só
-- produzia pendência parada em ABERTA por esquecimento, indistinguível de trabalho não feito.
--
-- Não há perda de auditoria: as linhas de historico_status que citam EM_ANDAMENTO ficam como
-- estão (status_anterior/status_novo são texto livre lá, sem CHECK), e continuam contando a
-- história de quem clicou no play enquanto ele existiu.

ALTER TABLE pendencia DROP CONSTRAINT ck_pendencia_status;

UPDATE pendencia SET status = 'ABERTA' WHERE status = 'EM_ANDAMENTO';

ALTER TABLE pendencia ADD CONSTRAINT ck_pendencia_status
    CHECK (status IN ('ABERTA', 'RESOLVIDA', 'CANCELADA'));
