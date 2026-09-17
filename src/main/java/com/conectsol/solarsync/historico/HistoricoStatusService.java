package com.conectsol.solarsync.historico;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;
import com.conectsol.solarsync.historico.dto.HistoricoStatusResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HistoricoStatusService {

    private final HistoricoStatusRepository historicoStatusRepository;

    /** Linha do tempo de um registro, do mais antigo para o mais recente. */
    @Transactional(readOnly = true)
    public List<HistoricoStatusResponse> listar(EntidadeTipo tipo, Long entidadeId) {
        return historicoStatusRepository.findByEntidadeTipoAndEntidadeId(tipo, entidadeId)
                .stream()
                .sorted(Comparator.comparing(HistoricoStatus::getOcorridoEm))
                .map(HistoricoStatusResponse::de)
                .toList();
    }

    public void registrar(EntidadeStatusEvent evento) {
        HistoricoStatus historico = HistoricoStatus.builder()
                .entidadeTipo(evento.getEntidadeTipo())
                .entidadeId(evento.getEntidadeId())
                .statusAnterior(evento.getStatusAnterior())
                .statusNovo(evento.getStatusNovo())
                .ocorridoEm(evento.getOcorridoEm())
                .usuarioId(evento.getUsuarioId())
                .build();

        historicoStatusRepository.save(historico);
    }
}
