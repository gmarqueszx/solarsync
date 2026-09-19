-- Modo conferência da leitura do e-mail da Coelba (solarsync.gmail.somente-conferencia).
--
-- A redação real do e-mail da Coelba nunca foi vista: as listas de termos de solarsync.coelba
-- são o provável, não o observado. Ligar a integração aplicando status direto significaria que
-- o primeiro erro de leitura reprova um projeto de verdade e suja o historico_status, que é de
-- onde saem todas as métricas do dashboard.
--
-- CONFERENCIA é o registro de "eu teria mudado este projeto, e não mudei": o ensaio que permite
-- conferir o que o parser entendeu de e-mails reais antes de ele passar a agir.
ALTER TABLE email_coelba DROP CONSTRAINT ck_email_coelba_resultado;

ALTER TABLE email_coelba ADD CONSTRAINT ck_email_coelba_resultado CHECK (resultado IN (
    'APLICADO', 'CONFERENCIA', 'SEM_ALTERACAO', 'NAO_RECONHECIDO', 'SEM_CORRESPONDENCIA',
    'AMBIGUO', 'TRANSICAO_INVALIDA', 'ERRO'));
