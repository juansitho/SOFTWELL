package com.softwell.softwell.config;

import com.softwell.softwell.service.DemoDatosService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Genera pedidos SINTÉTICOS al ARRANCAR la app (la lógica está en DemoDatosService).
 * La analítica no genera pedidos de demostración; este seeder solo se activa explícitamente al arrancar la app.
 *
 * Está APAGADO por defecto. Para usarlo, ejecuta la app con:
 *     softwell.seed-demo=true            → crea ~350 pedidos de los últimos 12 meses
 *     softwell.seed-demo.pedidos=3700    → (opcional) cantidad de pedidos
 *     softwell.seed-demo.dias=730        → (opcional) días de historia; 3700 pedidos en 730 días
 *                                          equivale al volumen con que se entrenó el modelo de demanda
 *     softwell.seed-demo.limpiar=true    → borra los pedidos demo (email @demo.softwell)
 *
 * Los pedidos demo se identifican por el dominio "@demo.softwell" en emailCliente,
 * por lo que NUNCA toca pedidos reales. No modifica el stock de los productos.
 */
@Component
@ConditionalOnProperty(name = "softwell.seed-demo", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private final DemoDatosService demoDatosService;

    @Value("${softwell.seed-demo.limpiar:false}")
    private boolean limpiar;

    /** Cantidad de pedidos demo y días de historia (por defecto 350 pedidos en 365 días). */
    @Value("${softwell.seed-demo.pedidos:350}")
    private int totalPedidos;

    @Value("${softwell.seed-demo.dias:365}")
    private int dias;

    public DemoDataSeeder(DemoDatosService demoDatosService) {
        this.demoDatosService = demoDatosService;
    }

    @Override
    public void run(String... args) {
        String msg = limpiar ? demoDatosService.borrar() : demoDatosService.generar(totalPedidos, dias);
        System.out.println("[DemoDataSeeder] " + msg);
    }
}
