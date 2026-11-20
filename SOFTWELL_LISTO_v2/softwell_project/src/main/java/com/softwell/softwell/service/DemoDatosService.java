package com.softwell.softwell.service;

import com.softwell.softwell.model.Pedido;
import com.softwell.softwell.model.Product;
import com.softwell.softwell.repository.PedidoRepository;
import com.softwell.softwell.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genera / borra pedidos SINTÉTICOS de demostración (para las gráficas, la minería de datos y la
 * predicción de demanda cuando la base real tiene pocos pedidos).
 *
 * Los pedidos demo se identifican por el dominio "@demo.softwell" en emailCliente, por lo que NUNCA
 * se tocan pedidos reales. No modifica el stock de los productos.
 * Lo usa DemoDataSeeder al arrancar con softwell.seed-demo=true; no forma parte del panel de analítica.
 */
@Service
public class DemoDatosService {

    public static final String DOMINIO = Pedido.DOMINIO_DEMOSTRACION;

    private final PedidoRepository pedidoRepository;
    private final ProductRepository productRepository;

    public DemoDatosService(PedidoRepository pedidoRepository, ProductRepository productRepository) {
        this.pedidoRepository = pedidoRepository;
        this.productRepository = productRepository;
    }

    private List<Pedido> pedidosDemo() {
        return pedidoRepository.findAll().stream()
                .filter(Pedido::esDeDemostracion)
                .collect(Collectors.toList());
    }

    public synchronized String borrar() {
        List<Pedido> demo = pedidosDemo();
        pedidoRepository.deleteAll(demo);
        return "Pedidos de demostración eliminados: " + demo.size() + ". Los pedidos reales no se tocaron.";
    }

    /** Crea {@code totalPedidos} pedidos demo repartidos en los últimos {@code dias} días. */
    public synchronized String generar(int totalPedidos, int dias) {
        long existentes = pedidosDemo().size();
        if (existentes > 0) {
            return "Ya existen " + existentes + " pedidos de demostración. No se generó nada "
                    + "(bórralos primero si quieres volver a generarlos).";
        }

        List<Product> productos = productRepository.findAll().stream()
                .filter(p -> p.getPrecio() > 0 && p.getNombre() != null
                        && (p.getEstadoAdmin() == null || "ACEPTADA".equals(p.getEstadoAdmin())))
                .sorted(Comparator.comparing(Product::getNombre))
                .collect(Collectors.toList());
        if (productos.size() < 4) {
            return "Se necesitan al menos 4 productos aceptados con precio para generar datos de demostración; hay "
                    + productos.size() + ".";
        }

        Random rnd = new Random(42);            // semilla fija → datos reproducibles
        int nClientes = 60;

        // Pesos de clientes tipo Pareto: pocos compran mucho, muchos compran poco
        double[] pesoCliente = new double[nClientes];
        double sumaPesos = 0;
        for (int i = 0; i < nClientes; i++) { pesoCliente[i] = 1.0 / Math.pow(i + 1, 0.9); sumaPesos += pesoCliente[i]; }

        // Popularidad de productos (también desigual) y "compañero" fijo para generar reglas de asociación
        double[] pesoProd = new double[productos.size()];
        double sumaProd = 0;
        for (int i = 0; i < productos.size(); i++) { pesoProd[i] = 1.0 / Math.pow(i + 1, 0.8); sumaProd += pesoProd[i]; }
        Map<Integer, Integer> companero = new HashMap<>();
        for (int i = 0; i < productos.size(); i++) companero.put(i, (i + 2) % productos.size());

        // Estacionalidad: Feb (San Valentín), May (Día de la Madre), Dic (Navidad)
        double[] estacion = {0.9, 1.4, 0.9, 0.9, 1.6, 1.0, 0.9, 0.9, 0.9, 1.0, 1.1, 1.9};

        LocalDateTime ahora = LocalDateTime.now();
        List<Pedido> nuevos = new ArrayList<>();

        for (int n = 0; n < totalPedidos; n++) {
            // fecha: más pedidos hacia los meses recientes (tendencia creciente) y con estacionalidad
            LocalDateTime fecha;
            do {
                double u = Math.pow(rnd.nextDouble(), 0.75);            // sesgo hacia 1 = reciente
                long minutosAtras = (long) ((1 - u) * dias * 24L * 60);
                fecha = ahora.minusMinutes(minutosAtras);
            } while (rnd.nextDouble() > estacion[fecha.getMonthValue() - 1] / 1.9);
            // más compras viernes-sábado
            int dow = fecha.getDayOfWeek().getValue();
            if ((dow == 2 || dow == 3) && rnd.nextDouble() < 0.35) fecha = fecha.plusDays(3);
            fecha = fecha.withHour(9 + rnd.nextInt(12)).withMinute(rnd.nextInt(60));
            if (fecha.isAfter(ahora)) fecha = ahora.minusHours(1);

            int cliente = elegirPonderado(pesoCliente, sumaPesos, rnd);

            // productos del pedido
            Map<Integer, Integer> carrito = new LinkedHashMap<>();
            int ancla = elegirPonderado(pesoProd, sumaProd, rnd);
            carrito.put(ancla, 1 + (rnd.nextDouble() < 0.15 ? 1 : 0));
            if (rnd.nextDouble() < 0.45) carrito.merge(companero.get(ancla), 1, Integer::sum);   // asociación fuerte
            if (rnd.nextDouble() < 0.15) carrito.merge(elegirPonderado(pesoProd, sumaProd, rnd), 1, Integer::sum);

            double subtotal = 0;
            StringBuilder desc = new StringBuilder(), imgs = new StringBuilder();
            int i = 0;
            for (Map.Entry<Integer, Integer> e : carrito.entrySet()) {
                Product p = productos.get(e.getKey());
                subtotal += p.getPrecio() * e.getValue();
                if (i++ > 0) { desc.append(", "); imgs.append(","); }
                desc.append(p.getNombre()).append(" (x").append(e.getValue()).append(")");
                imgs.append(p.getImagen() == null ? "" : p.getImagen());
            }

            double r = rnd.nextDouble();
            String estado = r < 0.70 ? "PAGADO" : r < 0.88 ? "PENDIENTE" : "RECHAZADO";

            Pedido ped = new Pedido();
            ped.setNombreCliente("Cliente Demo " + (cliente + 1));
            ped.setEmailCliente("cliente" + (cliente + 1) + DOMINIO);
            ped.setTelefonoCliente("300000" + String.format("%04d", cliente));
            ped.setDireccionCliente("Dirección demo " + (cliente + 1));
            ped.setFechaCreacion(fecha);
            ped.setEstado(estado);
            ped.setIva(subtotal * 0.19);          // mismo cálculo que CheckoutController
            ped.setCostoEnvio(Pedido.COSTO_ENVIO_ACTUAL);
            ped.setTotal(subtotal + subtotal * 0.19 + Pedido.COSTO_ENVIO_ACTUAL);
            ped.setDescripcionItems(desc.toString());
            ped.setImagenesItems(imgs.toString());
            nuevos.add(ped);
        }

        pedidoRepository.saveAll(nuevos);
        return "Se crearon " + nuevos.size() + " pedidos de demostración en los últimos " + dias
                + " días, usando " + productos.size() + " productos de tu catálogo. Los pedidos reales no se tocaron.";
    }

    private static int elegirPonderado(double[] pesos, double suma, Random rnd) {
        double x = rnd.nextDouble() * suma, acc = 0;
        for (int i = 0; i < pesos.length; i++) { acc += pesos[i]; if (x <= acc) return i; }
        return pesos.length - 1;
    }
}
