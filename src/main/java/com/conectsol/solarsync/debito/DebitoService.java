package com.conectsol.solarsync.debito;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
@RequiredArgsConstructor
public class DebitoService {

    private final DebitoRepository debitoRepository;
    private final ClienteRepository clienteRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Debito> listar(DebitoFiltro filtro, Pageable paginacao) {
        return debitoRepository.findAll(DebitoSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Debito buscarPorCliente(Long clienteId) {
        return debitoRepository.findByClienteId(clienteId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Nenhuma consulta de débito registrada para o cliente " + clienteId));
    }

    /**
     * Consulta o próprio banco, não a Coelba: é o retrato da última consulta que um analista
     * registrou. Usado por {@code ProjetoService} para barrar o envio à Coelba.
     * <p>
     * Cliente sem nenhum registro de débito conta como <b>sem débito</b> — barrar o envio por
     * falta de consulta travaria todo cliente novo, e a etapa 1 do fluxo já prevê seguir direto
     * quando não há nada pendente.
     */
    @Transactional(readOnly = true)
    public boolean clienteTemDebitoAtivo(Long clienteId) {
        return debitoRepository.existsByClienteIdAndStatus(clienteId, StatusDebito.ATIVO);
    }

    /**
     * Registra o resultado de uma consulta. Cria o registro do cliente na primeira vez e
     * atualiza nas seguintes (há no máximo um débito por cliente).
     */
    @Transactional
    public Debito registrarConsulta(Long clienteId, DebitoRegistrarRequest requisicao,
            Long usuarioId) {

        Instant consultadoEm = requisicao.consultadoEm() == null
                ? Instant.now()
                : requisicao.consultadoEm();

        Debito debito = debitoRepository.findByClienteId(clienteId).orElse(null);

        if (debito == null) {
            Cliente cliente = clienteRepository.findById(clienteId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Cliente não encontrado: " + clienteId));

            Debito novo = debitoRepository.save(Debito.builder()
                    .cliente(cliente)
                    .status(requisicao.status())
                    .ultimaConsultaEm(consultadoEm)
                    .build());

            publicar(novo, null, requisicao.status(), usuarioId);
            return novo;
        }

        StatusDebito statusAnterior = debito.getStatus();
        debito.setUltimaConsultaEm(consultadoEm);

        // Reconsultar e achar a mesma situação é atualização de data, não mudança de estado:
        // não publica evento, para não poluir o histórico e distorcer o tempo parado.
        if (statusAnterior == requisicao.status()) {
            return debitoRepository.save(debito);
        }

        debito.setStatus(requisicao.status());
        Debito salvo = debitoRepository.save(debito);
        publicar(salvo, statusAnterior, requisicao.status(), usuarioId);
        return salvo;
    }

    @Transactional
    public void excluir(Long id) {
        debitoRepository.delete(debitoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Débito não encontrado: " + id)));
    }

    private void publicar(Debito debito, StatusDebito anterior, StatusDebito novo,
            Long usuarioId) {
        eventPublisher.publishEvent(new DebitoStatusChangedEvent(
                debito.getId(), debito.getCliente().getId(), anterior, novo,
                Instant.now(), usuarioId));
    }
}
