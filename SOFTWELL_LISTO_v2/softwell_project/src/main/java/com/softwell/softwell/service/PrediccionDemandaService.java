package com.softwell.softwell.service;

import com.softwell.softwell.analytics.ModeloDemanda;
import com.softwell.softwell.model.Pedido;
import com.softwell.softwell.model.Product;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usa el modelo predictivo de demanda (ModeloDemanda) con los pedidos y productos reales de SOFTWELL.
 * Construye las variables requeridas por el modelo:
 * lag_1..4, medias móviles, unidades de la tienda la semana anterior,
 * semana del año, mes, categoría y precio. Solo cuentan pedidos PAGADO.
 *
 * Predice la semana EN CURSO (lunes a domingo) con las últimas ocho semanas completas.
 * Es apoyo al administrador: no compra ni modifica inventario.
 */
@Service
public class PrediccionDemandaService {

    private static final Pattern ITEM = Pattern.compile("\\s*(.+?) \\(x(\\d+)\\)(?:,|$)");
    private static final int SEMANAS_MODELO = 8;
    private static final int VENTANA = 8;
    private static final int UMBRAL_STOCK_BAJO = 10;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM");

    private final PedidoService pedidoService;
    private final ProductService productService;
    private volatile ModeloDemanda modelo;
    private volatile String errorCarga;

    public PrediccionDemandaService(PedidoService pedidoService, ProductService productService) {
        this.pedidoService = pedidoService;
        this.productService = productService;
    }

    public Map<String, Object> generar() {
        return generar(pedidoService.findAll(), productService.findAll(), LocalDate.now());
    }

    private synchronized ModeloDemanda modelo() {
        if (modelo == null && errorCarga == null) {
            try { modelo = ModeloDemanda.cargarDesdeRecurso(); }
            catch (Exception e) { errorCarga = e.getMessage(); }
        }
        return modelo;
    }

    private static Map<String, Object> noDisponible(String mensaje) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponible", false);
        m.put("mensaje", mensaje);
        return m;
    }

    private static double redondear1(double v) { return Math.round(v * 10.0) / 10.0; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generar(List<Pedido> pedidos, List<Product> todosProductos, LocalDate hoy) {
        ModeloDemanda mod = modelo();
        if (mod == null) return noDisponible("No se pudo cargar el modelo entrenado: " + errorCarga);

        // ---- catálogo: productos aceptados con nombre y precio (igual que el CSV de entrenamiento)
        Map<String, Product> catalogo = new LinkedHashMap<>();
        for (Product p : todosProductos) {
            if (p.getNombre() == null || p.getPrecio() <= 0) continue;
            if (p.getEstadoAdmin() != null && !"ACEPTADA".equals(p.getEstadoAdmin())) continue;
            catalogo.putIfAbsent(ModeloDemanda.clave(p.getNombre()), p);
        }
        if (catalogo.isEmpty()) return noDisponible("No hay productos aceptados con precio para estimar la demanda.");

        // ---- semanas: se predice la semana en curso con las semanas completas anteriores
        LocalDate objetivo = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate ultimaCompleta = objetivo.minusWeeks(1);

        LocalDate inicio = ultimaCompleta.minusWeeks(VENTANA - 1);

        // unidades vendidas (PAGADO) por producto y semana, más datos para el diagnóstico
        Map<String, Map<LocalDate, Integer>> ventas = new HashMap<>();
        Map<String, Integer> semanaActual = new HashMap<>();
        Map<String, Long> estados = new TreeMap<>();
        long pagados = 0, lineasReconocidas = 0;
        LocalDate ultimaVenta = null;
        for (Pedido p : pedidos) {
            estados.merge(p.getEstado() == null ? "(sin estado)" : p.getEstado(), 1L, Long::sum);
            if (p.getFechaCreacion() == null || !p.esPagado() || p.getDescripcionItems() == null) continue;
            pagados++;
            LocalDate dia = p.getFechaCreacion().toLocalDate();
            LocalDate sem = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Matcher m = ITEM.matcher(p.getDescripcionItems());
            while (m.find()) {
                String k = ModeloDemanda.clave(m.group(1));
                if (!catalogo.containsKey(k)) continue;           // producto renombrado o eliminado
                int cant = Integer.parseInt(m.group(2));
                lineasReconocidas++;
                if (ultimaVenta == null || dia.isAfter(ultimaVenta)) ultimaVenta = dia;
                if (sem.equals(objetivo)) semanaActual.merge(k, cant, Integer::sum);
                if (sem.isAfter(ultimaCompleta)) continue;
                ventas.computeIfAbsent(k, x -> new HashMap<>()).merge(sem, cant, Integer::sum);
            }
        }

        // serie semanal por producto en una ventana FIJA de VENTANA semanas completas:
        // índice 0 = la más antigua, VENTANA-1 = la última completa. Semanas sin ventas = 0.
        final int nSemanas = VENTANA;
        Map<String, int[]> serie = new LinkedHashMap<>();
        int[] tienda = new int[nSemanas];
        for (String k : catalogo.keySet()) {
            int[] u = new int[nSemanas];
            Map<LocalDate, Integer> v = ventas.getOrDefault(k, Map.of());
            for (int w = 0; w < nSemanas; w++) {
                u[w] = v.getOrDefault(inicio.plusWeeks(w), 0);
                tienda[w] += u[w];
            }
            serie.put(k, u);
        }
        long unidadesModelo = 0;
        for (int w = nSemanas - SEMANAS_MODELO; w < nSemanas; w++) unidadesModelo += tienda[w];
        if (unidadesModelo == 0) {
            Map<String, Object> out = noDisponible("No hay ventas pagadas en las últimas " + SEMANAS_MODELO
                    + " semanas completas (del " + ultimaCompleta.minusWeeks(SEMANAS_MODELO - 1) + " al "
                    + ultimaCompleta.plusDays(6) + "), por eso no se puede estimar la demanda. Registra pedidos pagados "
                    + "recientes para generar una estimación.");
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("pedidosTotales", pedidos.size());
            diag.put("pedidosPorEstado", estados);
            diag.put("pedidosPagados", pagados);
            diag.put("lineasDeProductosReconocidas", lineasReconocidas);
            diag.put("ultimaVentaPagada", ultimaVenta == null ? null : ultimaVenta.toString());
            diag.put("productosEnCatalogo", catalogo.size());
            out.put("diagnostico", diag);
            return out;
        }
        int semanasConHistorial = (int) Arrays.stream(tienda).filter(unidades -> unidades > 0).count();

        // ---- variables que no dependen del producto
        int semanaAnio = objetivo.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        double semSin = Math.sin(2 * Math.PI * semanaAnio / 52.18);
        double semCos = Math.cos(2 * Math.PI * semanaAnio / 52.18);
        int mes = objetivo.plusDays(3).getMonthValue();

        // ---- predicción por producto
        List<Map<String, Object>> filas = new ArrayList<>();
        double total = 0;
        int aReponer = 0, stockBajo = 0;
        for (Map.Entry<String, Product> e : catalogo.entrySet()) {
            int[] u = serie.get(e.getKey());
            Product prod = e.getValue();
            Map<String, Double> x = new HashMap<>();
            for (int l = 1; l <= 4; l++) x.put("lag_" + l, (double) u[nSemanas - l]);
            double media4 = (u[nSemanas - 1] + u[nSemanas - 2] + u[nSemanas - 3] + u[nSemanas - 4]) / 4.0;
            x.put("media_4", media4);
            double media8 = 0;
            for (int l = 1; l <= 8; l++) media8 += u[nSemanas - l];
            x.put("media_8", media8 / 8.0);
            x.put("unidades_tienda_lag1", (double) tienda[nSemanas - 1]);
            x.put("precio", prod.getPrecio());
            x.put("semana_sin", semSin);
            x.put("semana_cos", semCos);
            x.put("mes", (double) mes);

            double demanda = redondear1(mod.predecir(x, prod.getCategoria()));
            int stock = prod.getStock();
            int reponer = Math.max(0, (int) Math.ceil(demanda) - stock);
            String estado, mensaje;
            if (stock <= 0) { estado = "AGOTADO"; mensaje = "Falta stock"; stockBajo++; }
            else if (stock < UMBRAL_STOCK_BAJO) { estado = "POCO_STOCK"; mensaje = "Poco stock"; stockBajo++; }
            else { estado = "OK"; mensaje = "Stock suficiente"; }
            if (stock < demanda) aReponer++;
            total += demanda;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("producto", prod.getNombre());
            f.put("categoria", prod.getCategoria() == null ? "Sin categoría" : prod.getCategoria());
            f.put("demandaEstimada", demanda);
            f.put("vendidasSemanaActual", semanaActual.getOrDefault(e.getKey(), 0));
            f.put("stock", stock);
            f.put("reponer", reponer);
            f.put("estado", estado);
            f.put("mensaje", mensaje);
            filas.add(f);
        }
        filas.sort((a, b) -> Double.compare((double) b.get("demandaEstimada"), (double) a.get("demandaEstimada")));

        // ---- serie para la gráfica: últimas ocho semanas completas + semana estimada
        List<String> labels = new ArrayList<>();
        List<Integer> reales = new ArrayList<>();
        for (int w = 0; w < nSemanas; w++) {
            LocalDate semana = inicio.plusWeeks(w);
            labels.add(semana.format(FMT));
            reales.add(tienda[w]);
        }
        Map<String, Object> grafica = new LinkedHashMap<>();
        grafica.put("labels", labels);
        grafica.put("reales", reales);
        grafica.put("labelPrediccion", objetivo.format(FMT));
        grafica.put("prediccion", redondear1(total));

        // ---- información del modelo y advertencias
        Map<String, Object> meta = mod.metadatos();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("algoritmo", meta.get("algoritmo"));

        List<String> advertencias = new ArrayList<>();
        if (semanasConHistorial < SEMANAS_MODELO)
            advertencias.add("Solo hay " + semanasConHistorial + " semana(s) con ventas pagadas y el modelo usa " + SEMANAS_MODELO
                    + ". Las semanas sin ventas dentro de este periodo se cuentan como cero, así que la estimación es poco confiable.");
        Map<String, Object> agg = (Map<String, Object>) meta.getOrDefault("precision_agregada", Map.of());
        Object mediaEntrenamiento = agg.get("media_unidades_producto_semana");
        double recientes = 0;
        for (int[] u : serie.values()) for (int l = 1; l <= SEMANAS_MODELO; l++) recientes += u[nSemanas - l];
        double mediaActual = recientes / (SEMANAS_MODELO * (double) serie.size());
        if (mediaEntrenamiento instanceof Number n && n.doubleValue() > 0
                && (mediaActual < n.doubleValue() / 2 || mediaActual > n.doubleValue() * 2))
            advertencias.add(String.format(Locale.US, "El volumen de ventas reciente (%.1f unidades por producto y semana) es muy distinto al de los "
                    + "datos de entrenamiento (%.1f). Las estimaciones pueden ser poco precisas.", mediaActual, n.doubleValue()));

        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("demandaTotal", redondear1(total));
        resumen.put("productosAReponer", aReponer);
        resumen.put("productosConStockBajo", stockBajo);
        resumen.put("productos", filas.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disponible", true);
        out.put("semanaInicio", objetivo.toString());
        out.put("semanaFin", objetivo.plusDays(6).toString());
        out.put("resumen", resumen);
        out.put("modelo", info);
        out.put("advertencias", advertencias);
        out.put("grafica", grafica);
        out.put("predicciones", filas);
        return out;
    }
}
