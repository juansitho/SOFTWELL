package com.softwell.softwell.controller;

import com.softwell.softwell.model.Pedido;
import com.softwell.softwell.service.AnaliticaService;
import com.softwell.softwell.service.PedidoService;
import com.softwell.softwell.service.PrediccionDemandaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Rutas bajo /admin/** → ya protegidas por SecurityConfig (solo ADMIN). */
@Controller
@RequestMapping("/admin/analitica")
public class AnaliticaController {

    private final AnaliticaService analiticaService;
    private final PedidoService pedidoService;
    private final PrediccionDemandaService prediccionService;

    public AnaliticaController(AnaliticaService analiticaService, PedidoService pedidoService,
                               PrediccionDemandaService prediccionService) {
        this.analiticaService = analiticaService;
        this.pedidoService = pedidoService;
        this.prediccionService = prediccionService;
    }

    @GetMapping
    public String vista() {
        return "admin-analitica";
    }

    /** JSON que consumen las gráficas (Chart.js). */
    @GetMapping("/datos")
    @ResponseBody
    public Map<String, Object> datos() {
        return analiticaService.generar();
    }

    /** Predicción de demanda de la semana en curso con el modelo entrenado (Random Forest). */
    @GetMapping("/prediccion")
    @ResponseBody
    public Map<String, Object> prediccion() {
        return prediccionService.generar();
    }

    /** Dataset plano en CSV para llevarlo a Weka / Python / Power BI / Excel. */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportarCsv() {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("id,fecha,estado,cliente,email,total,iva,costo_envio,items\n");
        for (Pedido p : pedidoService.findAll()) {
            sb.append(csv(p.getId())).append(',')
              .append(p.getFechaCreacion() != null ? p.getFechaCreacion() : "").append(',')
              .append(csv(p.getEstado())).append(',')
              .append(csv(p.getNombreCliente())).append(',')
              .append(csv(p.getEmailCliente())).append(',')
              .append(p.getTotalActualizado()).append(',')
              .append(p.getIva()).append(',')
              .append(p.getCostoEnvioActualizado()).append(',')
              .append(csv(p.getDescripcionItems())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pedidos_softwell.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
