package com.conectsol.solarsync.projeto;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;

@ExtendWith(MockitoExtension.class)
class PendenciaResolvidaListenerTest {

    @Mock
    private ProjetoService projetoService;

    private PendenciaResolvidaListener listener;

    @Test
    void criaProjetoQuandoPendenciaFicaResolvida() {
        listener = new PendenciaResolvidaListener(projetoService);

        PendenciaStatusChangedEvent evento = new PendenciaStatusChangedEvent(
                1L, 10L, StatusPendencia.EM_ANDAMENTO, StatusPendencia.RESOLVIDA, Instant.now(), 99L);

        listener.aoResolverPendencia(evento);

        verify(projetoService).criarOuAtivarProjetoParaCliente(10L, 99L);
    }

    @Test
    void naoCriaProjetoParaOutrasTransicoesDeStatus() {
        listener = new PendenciaResolvidaListener(projetoService);

        PendenciaStatusChangedEvent evento = new PendenciaStatusChangedEvent(
                1L, 10L, StatusPendencia.ABERTA, StatusPendencia.EM_ANDAMENTO, Instant.now(), 99L);

        listener.aoResolverPendencia(evento);

        verify(projetoService, never()).criarOuAtivarProjetoParaCliente(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
