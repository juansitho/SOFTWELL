package com.softwell.softwell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PedidoTest {

    @Test
    void normalizesLegacyShippingAndOrderTotal() {
        Pedido pedido = new Pedido();
        pedido.setCostoEnvio(15.0);
        pedido.setTotal(1_240_015.0);

        assertEquals(15_000.0, pedido.getCostoEnvioActualizado());
        assertEquals(1_255_000.0, pedido.getTotalActualizado());
    }

    @Test
    void preservesTotalsAlreadyStoredWithCurrentShipping() {
        Pedido pedido = new Pedido();
        pedido.setCostoEnvio(Pedido.COSTO_ENVIO_ACTUAL);
        pedido.setTotal(1_255_000.0);

        assertEquals(15_000.0, pedido.getCostoEnvioActualizado());
        assertEquals(1_255_000.0, pedido.getTotalActualizado());
    }

    @Test
    void recognizesPaidStatusRegardlessOfCaseOrSurroundingSpaces() {
        Pedido pedido = new Pedido();
        pedido.setEstado(" pagado ");

        assertTrue(pedido.esPagado());
        assertEquals("PAGADO", pedido.getEstado());
    }
}
