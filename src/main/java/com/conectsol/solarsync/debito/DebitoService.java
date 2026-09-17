package com.conectsol.solarsync.debito;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.debito.dto.DebitoFiltro;
import com.conectsol.solarsync.debito.dto.DebitoRegistrarRequest;
import com.conectsol.solarsync.debito.event.DebitoStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Etapa 2 do fluxo: situação de débito do cliente na Coelba. Único ponto autorizado a mudar
 * essa situação, para que a auditoria dispare sempre — é dela que o dashboard extrai o tempo
 * que cada cliente ficou parado devendo.
 * <p>
 * Há um registro por cliente <b>por tipo</b> ({@link TipoDebito}): o débito que trava a
 * pendência e o que trava a homologação são consultas diferentes, feitas por pessoas diferentes
 * em momentos diferentes, e podem estar em situações diferentes ao mesmo tempo.
 */
@Service
@RequiredArgsConstructor
public class DebitoService {

    private final DebitoRepository debitoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Debito> listar(DebitoFiltro filtro, Pageable paginacao) {
        return debitoRepository.findAll(DebitoSpecs.de(filtro), paginacao);
    }

    /**
     * As duas situações do cliente. Devolve lista (e não um registro) porque a pergunta da tela
     * é "o que está travando este cliente", e a resposta pode ser as duas coisas, uma só, ou
     * nenhuma — inclusive nenhuma por ninguém ter consultado ainda.
     */
    @Transactional(readOnly = true)
    public List<Debito> listarPorCliente(Long clienteId) {
        // Ordem do enum = ordem do fluxo (pendência antes de homologação). Ordenar no banco
        // daria ordem alfabética do texto da coluna, que não diz nada. São no máximo dois.
        return debitoRepository.findByClienteId(clienteId).stream()
                .sorted(Comparator.comparing(Debito::getTipo))
                .toList();
    }

    @Transactional(readOnly = true)
    public Debito buscarPorCliente(Long clienteId, TipoDebito tipo) {
        return debitoRepository.findByClienteIdAndTipo(clienteId, tipo)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Nenhuma consulta de débito de " + tipo + " registrada para o cliente "
                                + clienteId));
    }

    /**
     * Consulta o próprio banco, não a Coelba: é o retrato da última consulta que um analista
     * registrou para <b>aquela etapa</b>. Usado por {@code PendenciaService} (tipo
     * {@code PENDENCIA}) e {@code ProjetoService} (tipo {@code HOMOLOGACAO}).
     */
    @Transactional(readOnly = true)
    public boolean clienteTemDebitoAtivo(Long clienteId, TipoDebito tipo) {
        return debitoRepository.existsByClienteIdAndTipoAndStatus(
                clienteId, tipo, StatusDebito.ATIVO);
    }

    /**
     * Se alguém já consultou a agência virtual para este cliente <b>nesta etapa</b>, qualquer
     * que tenha sido o resultado. Avançar a etapa exige <b>saber</b> se o cliente deve, e a
     * única forma de saber é ter consultado — a ausência de registro não é "não deve", é
     * "ninguém olhou", que é justamente o caso que se perde de vista.
     */
    @Transactional(readOnly = true)
    public boolean clienteTemConsultaRegistrada(Long clienteId, TipoDebito tipo) {
        return debitoRepository.existsByClienteIdAndTipo(clienteId, tipo);
    }

    /**
     * Registra o resultado de uma consulta. Cria o registro do par cliente+tipo na primeira vez
     * e atualiza nas seguintes.
     */
    @Transactional
    public Debito registrarConsulta(Long clienteId, DebitoRegistrarRequest requisicao,
            Long usuarioId) {

        Instant consultadoEm = requisicao.consultadoEm() == null
                ? Instant.now()
                : requisicao.consultadoEm();

        TipoDebito tipo = requisicao.tipo();
        Usuario consultadoPor = resolverUsuario(usuarioId);

        Debito debito = debitoRepository.findByClienteIdAndTipo(clienteId, tipo).orElse(null);

        if (debito == null) {
            Cliente cliente = clienteRepository.findById(clienteId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Cliente não encontrado: " + clienteId));

            Debito novo = debitoRepository.save(Debito.builder()
                    .cliente(cliente)
                    .tipo(tipo)
                    .status(requisicao.status())
                    .ultimaConsultaEm(consultadoEm)
                    .consultadoPor(consultadoPor)
                    .detectadoEm(requisicao.status() == StatusDebito.ATIVO ? consultadoEm : null)
                    .quitadoEm(requisicao.status() == StatusDebito.QUITADO ? consultadoEm : null)
                    .build());

            publicar(novo, null, requisicao.status(), consultadoEm, usuarioId);
            return novo;
        }

        StatusDebito statusAnterior = debito.getStatus();
        debito.setUltimaConsultaEm(consultadoEm);
        debito.setConsultadoPor(consultadoPor);

        // Reconsultar e achar a mesma situação é atualização de data, não mudança de estado:
        // não publica evento, para não poluir o histórico e distorcer o tempo parado.
        if (statusAnterior == requisicao.status()) {
            return debitoRepository.save(debito);
        }

        debito.setStatus(requisicao.status());
        if (requisicao.status() == StatusDebito.ATIVO) {
            // Débito reaberto começa a contar de novo: o relógio mede a espera de agora, não a
            // soma de todas as vezes que este cliente já deveu.
            debito.setDetectadoEm(consultadoEm);
            debito.setQuitadoEm(null);
        } else {
            // detectadoEm é mantido de propósito: quitado, a tela ainda mostra quanto tempo
            // aquilo travou o cliente.
            debito.setQuitadoEm(consultadoEm);
        }

        Debito salvo = debitoRepository.save(debito);
        publicar(salvo, statusAnterior, requisicao.status(), consultadoEm, usuarioId);
        return salvo;
    }

    @Transactional
    public void excluir(Long id) {
        debitoRepository.delete(debitoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Débito não encontrado: " + id)));
    }

    private Usuario resolverUsuario(Long usuarioId) {
        return usuarioId == null ? null : usuarioRepository.findById(usuarioId).orElse(null);
    }

    /**
     * O {@code ocorridoEm} do evento é a data da <b>consulta</b>, não a de quando alguém
     * digitou. A situação de débito mudou quando o analista a constatou na agência virtual —
     * registrar o instante da digitação faria a métrica "tempo parado por débito" medir a
     * agilidade de digitação em vez do tempo real de cobrança, e zeraria a métrica em toda
     * importação retroativa.
     */
    private void publicar(Debito debito, StatusDebito anterior, StatusDebito novo,
            Instant consultadoEm, Long usuarioId) {
        eventPublisher.publishEvent(new DebitoStatusChangedEvent(
                debito.getId(), debito.getCliente().getId(), anterior, novo,
                consultadoEm, usuarioId));
    }
}
