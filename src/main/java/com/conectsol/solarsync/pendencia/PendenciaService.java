package com.conectsol.solarsync.pendencia;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Único ponto autorizado a mudar o status de uma {@link Pendencia}. Qualquer origem da
 * mudança (tela, e-mail, webhook de CRM) deve passar por aqui, para que a automação de
 * avanço de etapa e a auditoria em historico_status disparem de forma consistente.
 */
@Service
@RequiredArgsConstructor
public class PendenciaService {

    private final PendenciaRepository pendenciaRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Pendencia atualizarStatus(Long pendenciaId, StatusPendencia novoStatus, Long usuarioId) {
        Pendencia pendencia = pendenciaRepository.findById(pendenciaId)
                .orElseThrow(() -> new EntityNotFoundException("Pendencia não encontrada: " + pendenciaId));

        StatusPendencia statusAnterior = pendencia.getStatus();
        pendencia.setStatus(novoStatus);
        if (novoStatus == StatusPendencia.RESOLVIDA) {
            pendencia.setResolvidoEm(Instant.now());
        }
        Pendencia salva = pendenciaRepository.save(pendencia);

        eventPublisher.publishEvent(new PendenciaStatusChangedEvent(
                salva.getId(),
                salva.getCliente().getId(),
                statusAnterior,
                novoStatus,
                Instant.now(),
                usuarioId));

        return salva;
    }
}
