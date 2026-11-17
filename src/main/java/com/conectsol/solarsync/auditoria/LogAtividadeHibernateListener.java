package com.conectsol.solarsync.auditoria;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.engine.spi.TransactionCompletionCallbacks.BeforeCompletionCallback;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.auditoria.dto.Alteracao;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.common.BaseEntity;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.debito.Debito;
import com.conectsol.solarsync.pendencia.Pendencia;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.unificacao.Unificacao;
import com.conectsol.solarsync.vistoria.Vistoria;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alimenta o {@code log_atividade} a partir dos eventos do próprio Hibernate: toda inserção,
 * alteração e exclusão das entidades do domínio, com o antes/depois de cada campo e o usuário do
 * token.
 * <p>
 * <b>Por que no Hibernate, e não nos services</b>: a regra arquitetural dos status (seção 3 do
 * CLAUDE.md) se apoia em todo mundo passar pelo service, e ela funciona porque são poucos pontos.
 * Edição de campo acontece em dezenas de métodos, e um log que depende de cada um lembrar de
 * chamá-lo é um log com buracos que ninguém vê. Aqui não há como esquecer: o que chega ao banco
 * pela JPA passa por este listener — tela, integração ou importação.
 * <p>
 * O que <b>não</b> passa: update em massa por JPQL/SQL nativo (o projeto não usa para dado de
 * negócio) e mudança só em coleção ({@code Usuario.papeis}) — esta já fica no
 * {@code historico_status} pelo {@code UsuarioAlteradoEvent}.
 * <p>
 * <b>Mesma transação, gravado no fim dela.</b> O insert vai num callback de antes do commit, que
 * o Hibernate roda depois do último flush: transação revertida não deixa log de algo que não
 * aconteceu, e transação confirmada não fica sem log. É o mesmo mecanismo do Hibernate Envers.
 * Vai por JDBC, e não por uma entidade, para não disparar este listener sobre si mesmo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogAtividadeHibernateListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Map<Class<?>, EntidadeTipo> TIPOS = Map.of(
            Cliente.class, EntidadeTipo.CLIENTE,
            Pendencia.class, EntidadeTipo.PENDENCIA,
            Debito.class, EntidadeTipo.DEBITO,
            Projeto.class, EntidadeTipo.PROJETO,
            Vistoria.class, EntidadeTipo.VISTORIA,
            Unificacao.class, EntidadeTipo.UNIFICACAO,
            Usuario.class, EntidadeTipo.USUARIO);

    /** Carimbos técnicos: mudam em toda gravação e não dizem nada a quem lê o log. */
    private static final Set<String> IGNORADOS = Set.of("criadoEm", "atualizadoEm");

    /** O hash nunca vai para o log — só o fato de a senha ter sido definida. */
    private static final String CAMPO_SENHA = "senhaHash";

    private static final String INSERT = """
            INSERT INTO log_atividade (usuario_id, usuario_nome, acao, entidade_tipo, entidade_id,
                                       descricao, alteracoes)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""";

    private final EntityManagerFactory entityManagerFactory;
    private final JsonMapper jsonMapper;

    @PostConstruct
    void registrar() {
        EventListenerRegistry registro = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .getService(EventListenerRegistry.class);
        registro.appendListeners(EventType.POST_INSERT, this);
        registro.appendListeners(EventType.POST_UPDATE, this);
        registro.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent evento) {
        EntidadeTipo tipo = tipoDe(evento.getEntity());
        if (tipo == null) {
            return;
        }
        String[] nomes = evento.getPersister().getPropertyNames();
        Object[] estado = evento.getState();
        List<Alteracao> alteracoes = new ArrayList<>();
        for (int i = 0; i < nomes.length; i++) {
            adicionar(alteracoes, nomes[i], null, estado[i]);
        }
        agendar(evento.getSession(), AcaoLog.CRIACAO, tipo, evento.getEntity(), evento.getId(),
                alteracoes);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent evento) {
        EntidadeTipo tipo = tipoDe(evento.getEntity());
        if (tipo == null) {
            return;
        }
        String[] nomes = evento.getPersister().getPropertyNames();
        Object[] anterior = evento.getOldState();
        Object[] novo = evento.getState();
        int[] sujos = evento.getDirtyProperties();

        List<Alteracao> alteracoes = new ArrayList<>();
        if (sujos != null) {
            for (int i : sujos) {
                adicionar(alteracoes, nomes[i], anterior == null ? null : anterior[i], novo[i]);
            }
        } else if (anterior != null) {
            for (int i = 0; i < nomes.length; i++) {
                adicionar(alteracoes, nomes[i], anterior[i], novo[i]);
            }
        }
        // Só carimbo técnico mudou (ou nada visível mudou): não é atividade de ninguém.
        if (alteracoes.isEmpty()) {
            return;
        }
        agendar(evento.getSession(), AcaoLog.EDICAO, tipo, evento.getEntity(), evento.getId(),
                alteracoes);
    }

    @Override
    public void onPostDelete(PostDeleteEvent evento) {
        EntidadeTipo tipo = tipoDe(evento.getEntity());
        if (tipo == null) {
            return;
        }
        String[] nomes = evento.getPersister().getPropertyNames();
        Object[] estado = evento.getDeletedState();
        List<Alteracao> alteracoes = new ArrayList<>();
        if (estado != null) {
            for (int i = 0; i < nomes.length; i++) {
                adicionar(alteracoes, nomes[i], estado[i], null);
            }
        }
        agendar(evento.getSession(), AcaoLog.EXCLUSAO, tipo, evento.getEntity(), evento.getId(),
                alteracoes);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private static EntidadeTipo tipoDe(Object entidade) {
        return entidade == null ? null : TIPOS.get(entidade.getClass());
    }

    private static void adicionar(List<Alteracao> alteracoes, String campo, Object anterior,
            Object novo) {
        if (IGNORADOS.contains(campo) || anterior instanceof Collection<?>
                || novo instanceof Collection<?>) {
            return;
        }
        if (CAMPO_SENHA.equals(campo)) {
            if (!Objects.equals(anterior, novo) && novo != null) {
                alteracoes.add(new Alteracao("senha", anterior == null ? null : "definida",
                        "redefinida"));
            }
            return;
        }
        String de = formatar(anterior);
        String para = formatar(novo);
        if (!Objects.equals(de, para)) {
            alteracoes.add(new Alteracao(campo, de, para));
        }
    }

    static String formatar(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof Usuario usuario) {
            return usuario.getNome();
        }
        if (valor instanceof Cliente cliente) {
            return cliente.getNome();
        }
        if (valor instanceof BaseEntity entidade) {
            return "#" + entidade.getId();
        }
        if (valor instanceof Enum<?> enumerado) {
            return enumerado.name();
        }
        if (valor instanceof java.math.BigDecimal numero) {
            return numero.stripTrailingZeros().toPlainString();
        }
        String texto = valor.toString();
        return texto.isBlank() ? null : texto;
    }

    /** O nome que identifica o registro para quem lê o log: o cliente, ou o próprio usuário. */
    static String descricaoDe(Object entidade) {
        try {
            Cliente cliente = switch (entidade) {
                case Cliente c -> c;
                case Pendencia p -> p.getCliente();
                case Debito d -> d.getCliente();
                case Projeto p -> p.getCliente();
                case Unificacao u -> u.getCliente();
                case Vistoria v -> v.getProjeto() == null ? null : v.getProjeto().getCliente();
                case Usuario u -> null;
                default -> null;
            };
            if (entidade instanceof Usuario usuario) {
                return limitar(usuario.getNome());
            }
            return cliente == null ? null : limitar(cliente.getNome());
        } catch (RuntimeException e) {
            // Descrição é conveniência de leitura; nunca vale derrubar a gravação por ela.
            log.warn("Sem descrição para o log de {}: {}", entidade.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static String limitar(String texto) {
        return texto == null || texto.length() <= 300 ? texto : texto.substring(0, 300);
    }

    private void agendar(SharedSessionContractImplementor sessao, AcaoLog acao, EntidadeTipo tipo,
            Object entidade, Object id, List<Alteracao> alteracoes) {

        Autor autor = autorAtual();
        String descricao = descricaoDe(entidade);
        String json = jsonMapper.writeValueAsString(alteracoes);
        long entidadeId = ((Number) id).longValue();

        sessao.getTransactionCompletionCallbacks().registerCallback(
                (BeforeCompletionCallback) s -> s.doWork(conexao -> {
                    try (PreparedStatement ps = conexao.prepareStatement(INSERT)) {
                        if (autor.id() == null) {
                            ps.setNull(1, Types.BIGINT);
                        } else {
                            ps.setLong(1, autor.id());
                        }
                        ps.setString(2, autor.nome());
                        ps.setString(3, acao.name());
                        ps.setString(4, tipo.name());
                        ps.setLong(5, entidadeId);
                        ps.setString(6, descricao);
                        ps.setString(7, json);
                        ps.executeUpdate();
                    }
                }));
    }

    /**
     * Quem está no token da requisição. Sem token — job de integração, bootstrap, dados de
     * exemplo — o autor fica nulo, e a tela mostra "Sistema". Os listeners {@code AFTER_COMMIT}
     * rodam na mesma thread da requisição, então o projeto criado sozinho quando a pendência é
     * resolvida fica atribuído a quem resolveu a pendência.
     */
    private static Autor autorAtual() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao instanceof JwtAuthenticationToken token) {
            try {
                return new Autor(Long.valueOf(token.getToken().getSubject()),
                        token.getToken().getClaimAsString("nome"));
            } catch (NumberFormatException e) {
                return new Autor(null, token.getName());
            }
        }
        return new Autor(null, null);
    }

    private record Autor(Long id, String nome) {
    }
}
