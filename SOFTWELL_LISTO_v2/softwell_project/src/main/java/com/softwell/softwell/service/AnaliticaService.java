package com.softwell.softwell.service;

import com.softwell.softwell.analytics.Mineria;
import com.softwell.softwell.model.Pedido;
import com.softwell.softwell.model.Product;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Construye todos los datasets que consume la página /admin/analitica.
 * Trabaja solo con colecciones ya existentes (pedidos y products), no cambia el modelo.
 */
@Service
public class AnaliticaService {

    // "Collar Brillante (x2), Anillo Blanco (x1)"
    private static final Pattern ITEM = Pattern.compile("\\s*(.+?) \\(x(\\d+)\\)(?:,|$)");
    private static final String[] DIAS = {"Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"};

    private final PedidoService pedidoService;
    private final ProductService productService;
    private final UsuarioService usuarioService;

    public AnaliticaService(PedidoService pedidoService, ProductService productService, UsuarioService usuarioService) {
        this.pedidoService = pedidoService;
        this.productService = productService;
        this.usuarioService = usuarioService;
    }

    /** Línea de un pedido ya "desarmada": producto + cantidad. */
    private record Linea(String producto, int cantidad) {}

    public Map<String, Object> generar() {
        List<Pedido> todos = pedidoService.findAll();
        List<Pedido> conFecha = todos.stream()
                .filter(p -> p.getFechaCreacion() != null)
                .collect(Collectors.toList());
        List<Pedido> pagados = conFecha.stream().filter(Pedido::esPagado).collect(Collectors.toList());
        List<Pedido> todosPagados = todos.stream().filter(Pedido::esPagado).collect(Collectors.toList());
        List<Product> productos = productService.findAll();

        Map<String, Product> porNombre = new HashMap<>();
        for (Product p : productos) if (p.getNombre() != null) porNombre.put(clave(p.getNombre()), p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis(todos, todosPagados, usuarioService.obtenerTodosLosClientes().size()));
        out.put("ventasMensuales", ventasMensuales(pagados));
        out.put("estados", estados(todos));
        out.put("diaSemana", diaSemana(conFecha));
        out.put("categorias", categorias(pagados, porNombre));
        out.put("topProductos", topProductos(pagados, porNombre));
        out.put("pareto", pareto(pagados, porNombre));
        out.put("margenCategoria", margenCategoria(productos));
        out.put("segmentacion", segmentacion(pagados));
        out.put("asociacion", asociacion(pagados));
        out.put("hallazgos", hallazgos(out));
        return out;
    }

    // ------------------------------------------------------------ utilidades
    private static String clave(String s) {
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private static List<Linea> lineas(Pedido p) {
        List<Linea> res = new ArrayList<>();
        if (p.getDescripcionItems() == null) return res;
        Matcher m = ITEM.matcher(p.getDescripcionItems());
        while (m.find()) res.add(new Linea(m.group(1).trim(), Integer.parseInt(m.group(2))));
        return res;
    }

    private static double redondear(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String claveCliente(Pedido p) {
        if (p.getEmailCliente() != null && !p.getEmailCliente().isBlank()) return p.getEmailCliente().trim().toLowerCase();
        if (p.getUsuarioId() != null) return p.getUsuarioId();
        return p.getNombreCliente() != null ? p.getNombreCliente() : "anonimo";
    }

    // ------------------------------------------------------------------ KPIs
    private Map<String, Object> kpis(List<Pedido> todos, List<Pedido> pagados, int clientesRegistrados) {
        double ingresos = pagados.stream().mapToDouble(Pedido::getTotalActualizado).sum();
        long unidades = pagados.stream().flatMap(p -> lineas(p).stream()).mapToLong(Linea::cantidad).sum();

        Map<String, Object> k = new LinkedHashMap<>();
        k.put("ingresos", redondear(ingresos));
        k.put("pedidosTotales", todos.size());
        k.put("pedidosPagados", pagados.size());
        k.put("ticketPromedio", pagados.isEmpty() ? 0 : redondear(ingresos / pagados.size()));
        k.put("tasaConversion", todos.isEmpty() ? 0 : redondear(100.0 * pagados.size() / todos.size()));
        k.put("unidadesVendidas", unidades);
        k.put("clientesActivos", clientesRegistrados);
        return k;
    }

    // ------------------------------------- Serie de tiempo + regresión lineal
    private Map<String, Object> ventasMensuales(List<Pedido> pagados) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pagados.isEmpty()) { out.put("labels", List.of()); out.put("ingresos", List.of()); out.put("pedidos", List.of()); return out; }

        TreeMap<YearMonth, double[]> mapa = new TreeMap<>();
        for (Pedido p : pagados) {
            double[] acc = mapa.computeIfAbsent(YearMonth.from(p.getFechaCreacion()), k -> new double[2]);
            acc[0] += p.getTotalActualizado();
            acc[1]++;
        }
        // rellenar meses sin ventas con 0 para que la serie sea continua
        YearMonth ini = mapa.firstKey(), fin = mapa.lastKey();
        for (YearMonth m = ini; !m.isAfter(fin); m = m.plusMonths(1)) mapa.putIfAbsent(m, new double[2]);

        List<String> labels = new ArrayList<>();
        List<Double> ingresos = new ArrayList<>();
        List<Integer> pedidos = new ArrayList<>();
        mapa.forEach((m, v) -> { labels.add(m.toString()); ingresos.add(redondear(v[0])); pedidos.add((int) v[1]); });

        out.put("labels", labels);
        out.put("ingresos", ingresos);
        out.put("pedidos", pedidos);

        // Regresión sobre ingresos mensuales + pronóstico de 3 meses
        double[] y = ingresos.stream().mapToDouble(Double::doubleValue).toArray();
        // El mes en curso está incompleto: se dibuja, pero NO entra al ajuste de la recta
        boolean mesParcial = fin.equals(YearMonth.now());
        double[] yAjuste = mesParcial ? Arrays.copyOf(y, y.length - 1) : y;
        if (yAjuste.length >= 3) {
            Mineria.Regresion reg = Mineria.regresionLineal(yAjuste);
            List<Double> tendencia = new ArrayList<>();
            for (int i = 0; i < y.length; i++) tendencia.add(redondear(reg.predecir(i)));
            List<String> labelsFut = new ArrayList<>();
            List<Double> pronostico = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                labelsFut.add(fin.plusMonths(i).toString());
                pronostico.add(redondear(Math.max(0, reg.predecir(y.length - 1 + i))));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tendencia", tendencia);
            r.put("labelsFuturos", labelsFut);
            r.put("pronostico", pronostico);
            r.put("pendiente", redondear(reg.pendiente()));
            r.put("intercepto", redondear(reg.intercepto()));
            r.put("r2", Math.round(reg.r2() * 1000.0) / 1000.0);
            r.put("mesParcialExcluido", mesParcial);
            out.put("regresion", r);
        }
        return out;
    }

    // -------------------------------------------------------- Distribuciones
    private Map<String, Object> estados(List<Pedido> todos) {
        Map<String, Long> c = todos.stream().collect(Collectors.groupingBy(
                p -> {
                    String estado = Pedido.normalizarEstado(p.getEstado());
                    return estado == null || estado.isEmpty() ? "SIN ESTADO" : estado;
                }, TreeMap::new, Collectors.counting()));
        return Map.of("labels", new ArrayList<>(c.keySet()), "valores", new ArrayList<>(c.values()));
    }

    private Map<String, Object> diaSemana(List<Pedido> todos) {
        int[] cont = new int[7];
        for (Pedido p : todos) cont[p.getFechaCreacion().getDayOfWeek().getValue() - 1]++;
        List<Integer> v = new ArrayList<>();
        for (int x : cont) v.add(x);
        return Map.of("labels", Arrays.asList(DIAS), "valores", v);
    }

    private Map<String, Object> categorias(List<Pedido> pagados, Map<String, Product> porNombre) {
        Map<String, Double> ingresos = new TreeMap<>();
        for (Pedido p : pagados)
            for (Linea l : lineas(p)) {
                Product prod = porNombre.get(clave(l.producto()));
                String cat = prod != null && prod.getCategoria() != null ? prod.getCategoria() : "Sin categoría";
                ingresos.merge(cat, (prod != null ? prod.getPrecio() : 0) * l.cantidad(), Double::sum);
            }
        List<Map.Entry<String, Double>> ord = new ArrayList<>(ingresos.entrySet());
        ord.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return Map.of("labels", ord.stream().map(Map.Entry::getKey).collect(Collectors.toList()),
                "valores", ord.stream().map(e -> redondear(e.getValue())).collect(Collectors.toList()));
    }

    // --------------------------------------------- Top productos y Pareto/ABC
    private record Venta(String nombre, int unidades, double ingresos) {}

    private List<Venta> ventasPorProducto(List<Pedido> pagados, Map<String, Product> porNombre) {
        Map<String, int[]> unidades = new HashMap<>();
        Map<String, Double> ingresos = new HashMap<>();
        for (Pedido p : pagados)
            for (Linea l : lineas(p)) {
                Product prod = porNombre.get(clave(l.producto()));
                unidades.computeIfAbsent(l.producto(), k -> new int[1])[0] += l.cantidad();
                ingresos.merge(l.producto(), (prod != null ? prod.getPrecio() : 0) * l.cantidad(), Double::sum);
            }
        List<Venta> res = new ArrayList<>();
        unidades.forEach((n, u) -> res.add(new Venta(n, u[0], ingresos.get(n))));
        res.sort(Comparator.comparingDouble(Venta::ingresos).reversed());
        return res;
    }

    private Map<String, Object> topProductos(List<Pedido> pagados, Map<String, Product> porNombre) {
        List<Venta> top = ventasPorProducto(pagados, porNombre).stream()
                .sorted(Comparator.comparingInt(Venta::unidades).reversed()
                        .thenComparing(Comparator.comparingDouble(Venta::ingresos).reversed()))
                .limit(10)
                .collect(Collectors.toList());
        return Map.of("labels", top.stream().map(Venta::nombre).collect(Collectors.toList()),
                "unidades", top.stream().map(Venta::unidades).collect(Collectors.toList()),
                "ingresos", top.stream().map(v -> redondear(v.ingresos())).collect(Collectors.toList()));
    }

    private Map<String, Object> pareto(List<Pedido> pagados, Map<String, Product> porNombre) {
        List<Venta> ventas = ventasPorProducto(pagados, porNombre);
        double total = ventas.stream().mapToDouble(Venta::ingresos).sum();
        List<String> labels = new ArrayList<>(), clase = new ArrayList<>();
        List<Double> valores = new ArrayList<>(), acumulado = new ArrayList<>();
        double acc = 0;
        int a = 0, b = 0, c = 0;
        for (Venta v : ventas) {
            double antes = total == 0 ? 0 : acc / total;   // % acumulado ANTES de sumar este producto
            acc += v.ingresos();
            String cl = antes < 0.80 ? "A" : antes < 0.95 ? "B" : "C";
            if (cl.equals("A")) a++; else if (cl.equals("B")) b++; else c++;
            labels.add(v.nombre()); clase.add(cl);
            valores.add(redondear(v.ingresos()));
            acumulado.add(total == 0 ? 0 : redondear(100.0 * acc / total));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", labels); out.put("valores", valores);
        out.put("acumulado", acumulado); out.put("clase", clase);
        out.put("resumen", Map.of("A", a, "B", b, "C", c));
        return out;
    }

    // -------------------------------------------------- Margen por categoría
    private Map<String, Object> margenCategoria(List<Product> productos) {
        Map<String, List<Double>> m = new TreeMap<>();
        for (Product p : productos) {
            if (!"ACEPTADA".equals(p.getEstadoAdmin())) continue;
            if (p.getPrecio() <= 0 || p.getCostoProveedor() <= 0 || p.getCategoria() == null) continue;
            m.computeIfAbsent(p.getCategoria(), k -> new ArrayList<>())
                    .add(100.0 * (p.getPrecio() - p.getCostoProveedor()) / p.getPrecio());
        }
        List<String> labels = new ArrayList<>(m.keySet());
        List<Double> vals = labels.stream()
                .map(k -> redondear(m.get(k).stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                .collect(Collectors.toList());
        return Map.of("labels", labels, "valores", vals);
    }

    // ------------------------------------ Segmentación RFM con K-Means (k=3)
    private Map<String, Object> segmentacion(List<Pedido> pagados) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<Pedido>> porCliente = pagados.stream().collect(Collectors.groupingBy(
                AnaliticaService::claveCliente, LinkedHashMap::new, Collectors.toList()));
        if (porCliente.size() < 3) {
            out.put("suficiente", false);
            out.put("mensaje", "Se necesitan al menos 3 clientes con pedidos pagados para segmentar.");
            return out;
        }

        LocalDateTime ref = pagados.stream().map(Pedido::getFechaCreacion).max(Comparator.naturalOrder()).get();
        List<String> clientes = new ArrayList<>(porCliente.keySet());
        double[][] rfm = new double[clientes.size()][3];
        for (int i = 0; i < clientes.size(); i++) {
            List<Pedido> ps = porCliente.get(clientes.get(i));
            LocalDateTime ultima = ps.stream().map(Pedido::getFechaCreacion).max(Comparator.naturalOrder()).get();
            rfm[i][0] = ChronoUnit.DAYS.between(ultima, ref);         // Recencia (días)
            rfm[i][1] = ps.size();                                     // Frecuencia
            rfm[i][2] = ps.stream().mapToDouble(Pedido::getTotalActualizado).sum(); // Monetario
        }
        double[][] norm = Mineria.normalizar(rfm);

        // Método del codo: inercia para k = 1..6
        List<Double> codo = new ArrayList<>();
        for (int k = 1; k <= Math.min(6, clientes.size()); k++)
            codo.add(redondear(Mineria.kmeans(norm, k, 100).inercia()));

        int k = 3;
        Mineria.KMeansResultado res = Mineria.kmeans(norm, k, 100);

        // Puntaje del segmento: (1 - R) + F + M sobre los centroides → ordena de mejor a peor
        Integer[] orden = {0, 1, 2};
        double[] puntaje = new double[k];
        for (int c = 0; c < k; c++) puntaje[c] = (1 - res.centroides()[c][0]) + res.centroides()[c][1] + res.centroides()[c][2];
        Arrays.sort(orden, (x, y) -> Double.compare(puntaje[y], puntaje[x]));
        String[] nombres = {"VIP / Campeones", "Regulares", "En riesgo / Dormidos"};
        String[] nombrePorCluster = new String[k];
        for (int r = 0; r < k; r++) nombrePorCluster[orden[r]] = nombres[r];

        List<Map<String, Object>> puntos = new ArrayList<>();
        for (int i = 0; i < clientes.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cliente", clientes.get(i));
            p.put("recencia", (int) rfm[i][0]);
            p.put("frecuencia", (int) rfm[i][1]);
            p.put("monto", redondear(rfm[i][2]));
            p.put("segmento", nombrePorCluster[res.asignacion()[i]]);
            puntos.add(p);
        }

        List<Map<String, Object>> resumen = new ArrayList<>();
        for (int r = 0; r < k; r++) {
            int c = orden[r];
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < clientes.size(); i++) if (res.asignacion()[i] == c) idx.add(i);
            if (idx.isEmpty()) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("segmento", nombres[r]);
            s.put("clientes", idx.size());
            s.put("recenciaProm", redondear(idx.stream().mapToDouble(i -> rfm[i][0]).average().orElse(0)));
            s.put("frecuenciaProm", redondear(idx.stream().mapToDouble(i -> rfm[i][1]).average().orElse(0)));
            s.put("montoProm", redondear(idx.stream().mapToDouble(i -> rfm[i][2]).average().orElse(0)));
            s.put("montoTotal", redondear(idx.stream().mapToDouble(i -> rfm[i][2]).sum()));
            resumen.add(s);
        }

        out.put("suficiente", true);
        out.put("puntos", puntos);
        out.put("resumen", resumen);
        out.put("codo", codo);
        return out;
    }

    // ----------------------------- Reglas de asociación (canasta de compra)
    private Map<String, Object> asociacion(List<Pedido> pagados) {
        List<Set<String>> transacciones = new ArrayList<>();
        for (Pedido p : pagados) {
            Set<String> s = lineas(p).stream().map(Linea::producto).collect(Collectors.toCollection(TreeSet::new));
            if (!s.isEmpty()) transacciones.add(s);
        }
        long conVarios = transacciones.stream().filter(s -> s.size() >= 2).count();
        List<Mineria.Regla> reglas = Mineria.reglasAsociacion(transacciones, 2, 0.20);

        List<Map<String, Object>> filas = reglas.stream().limit(10).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("si", r.antecedente());
            m.put("entonces", r.consecuente());
            m.put("conteo", r.conteoPar());
            m.put("soporte", redondear(r.soporte() * 100));
            m.put("confianza", redondear(r.confianza() * 100));
            m.put("lift", redondear(r.lift()));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transacciones", transacciones.size());
        out.put("transaccionesMultiples", conVarios);
        out.put("reglas", filas);
        return out;
    }

    // ------------------------------------------- Hallazgos en lenguaje natural
    @SuppressWarnings("unchecked")
    private List<String> hallazgos(Map<String, Object> d) {
        List<String> h = new ArrayList<>();

        Map<String, Object> vm = (Map<String, Object>) d.get("ventasMensuales");
        if (vm.get("regresion") != null) {
            Map<String, Object> r = (Map<String, Object>) vm.get("regresion");
            double pend = (double) r.get("pendiente");
            h.add("Las ventas mensuales " + (pend >= 0 ? "crecen" : "decrecen") + " en promedio COP $ "
                    + String.format(Locale.GERMANY, "%,.0f", Math.abs(pend)) + " por mes (R² = " + r.get("r2") + ").");
        }

        Map<String, Object> par = (Map<String, Object>) d.get("pareto");
        Map<String, Integer> res = (Map<String, Integer>) par.get("resumen");
        int total = res.get("A") + res.get("B") + res.get("C");
        if (total > 0)
            h.add(res.get("A") + " de " + total + " productos (clase A) generan cerca del 80% de los ingresos.");

        Map<String, Object> seg = (Map<String, Object>) d.get("segmentacion");
        if (Boolean.TRUE.equals(seg.get("suficiente"))) {
            List<Map<String, Object>> sr = (List<Map<String, Object>>) seg.get("resumen");
            if (!sr.isEmpty()) {
                Map<String, Object> vip = sr.get(0);
                h.add("El segmento \"" + vip.get("segmento") + "\" tiene " + vip.get("clientes")
                        + " cliente(s) y aporta COP $ " + String.format(Locale.GERMANY, "%,.0f", (double) vip.get("montoTotal")) + " en compras.");
            }
        }

        Map<String, Object> aso = (Map<String, Object>) d.get("asociacion");
        List<Map<String, Object>> reglas = (List<Map<String, Object>>) aso.get("reglas");
        if (!reglas.isEmpty()) {
            Map<String, Object> r = reglas.get(0);
            h.add("Regla más fuerte: quien compra \"" + r.get("si") + "\" también compra \"" + r.get("entonces")
                    + "\" el " + r.get("confianza") + "% de las veces (lift " + r.get("lift") + ").");
        }
        return h;
    }
}
