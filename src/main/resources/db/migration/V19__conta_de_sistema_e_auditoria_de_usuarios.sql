-- Achado A-01 da auditoria de segurança (docs/security-audit): a conta técnica das integrações
-- (V13) passava pela única guarda da gestão de usuários, que só protegia administradores. Um
-- GESTOR conseguia reativá-la, dar-lhe senha e papel e entrar por ela, gravando no
-- historico_status ações indistinguíveis das da automação.
--
-- A marca vira coluna, e não comparação de e-mail no código, porque é uma propriedade da conta:
-- a próxima conta técnica (um webhook, um importador) nasce marcada sem ninguém lembrar de
-- acrescentar mais um e-mail numa lista.
ALTER TABLE usuario ADD COLUMN conta_sistema BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE usuario SET conta_sistema = TRUE WHERE lower(email) = 'integracao@conectsol.com';

-- Devolve a conta ao estado da V13 caso ela já tenha sido reativada: sem senha, inativa e sem
-- papel. Em banco nenhum isto deveria mudar algo — e se mudar, é exatamente o caso a desfazer.
UPDATE usuario SET ativo = FALSE, senha_hash = NULL WHERE conta_sistema;
DELETE FROM usuario_papel WHERE usuario_id IN (SELECT id FROM usuario WHERE conta_sistema);

-- A gestão de usuários passa a deixar trilha: quem ativou, desativou, redefiniu senha ou mudou
-- papéis de quem. Vai para o mesmo historico_status dos outros módulos — o dashboard filtra por
-- entidade_tipo em toda consulta, então as linhas USUARIO não entram em métrica nenhuma.
ALTER TABLE historico_status DROP CONSTRAINT ck_historico_entidade_tipo;
ALTER TABLE historico_status ADD CONSTRAINT ck_historico_entidade_tipo CHECK (entidade_tipo IN
    ('CLIENTE', 'PENDENCIA', 'DEBITO', 'PROJETO', 'VISTORIA', 'UNIFICACAO', 'USUARIO'));
