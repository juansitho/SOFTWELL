package com.softwell.softwell.controller;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.softwell.softwell.model.CartItem;
import com.softwell.softwell.model.Pedido;
import com.softwell.softwell.model.Usuario;
import com.softwell.softwell.service.CartService;
import com.softwell.softwell.service.EmailService;
import com.softwell.softwell.service.PedidoService;
import com.softwell.softwell.service.UsuarioService;

@Controller
@RequestMapping("/checkout")
public class CheckoutController {

    private static final Logger logger = LoggerFactory.getLogger(CheckoutController.class);

    private static final Set<String> BANCOS_PSE = Set.of(
            "bancolombia", "bogota", "davivienda", "bbva", "occidente", "popular",
            "av-villas", "caja-social", "itau", "scotiabank-colpatria", "gnb-sudameris",
            "agrario", "pichincha", "falabella", "finandina", "nu", "serfinanza",
            "bancoomeva", "mundo-mujer", "w", "cooperativo");

    private final PedidoService pedidoService;
    private final UsuarioService usuarioService;
    private final CartService cartService;
    private final EmailService emailService;

    public CheckoutController(
            PedidoService pedidoService,
            UsuarioService usuarioService,
            CartService cartService,
            EmailService emailService) {
        this.pedidoService = pedidoService;
        this.usuarioService = usuarioService;
        this.cartService = cartService;
        this.emailService = emailService;
    }

    @GetMapping
    public String resumen(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        if (userDetails == null) {
            return "redirect:/login";
        }

        // No permitir entrar al resumen si el carrito está vacío
        if (cartService.isEmpty()) {
            return "redirect:/carrito";
        }

        Usuario usuario = usuarioService.obtenerPorEmail(userDetails.getUsername());
        if (usuario == null) {
            return "redirect:/login";
        }

        List<CartItem> items = cartService.getItems();
        double subtotal = cartService.getTotal();
        double iva = subtotal * 0.19;
        double envio = Pedido.COSTO_ENVIO_ACTUAL;

        model.addAttribute("usuario", usuario);
        model.addAttribute("items", items);
        model.addAttribute("subtotal", subtotal);
        model.addAttribute("iva", iva);
        model.addAttribute("envio", envio);
        model.addAttribute("totalFinal", subtotal + iva + envio);

        return "checkout";
    }

    @PostMapping("/confirmar")
    public String procesarPago(@AuthenticationPrincipal UserDetails userDetails,
                               @RequestParam(required = false) String metodoPago,
                               @RequestParam(required = false) String titularTarjeta,
                               @RequestParam(required = false) String numeroTarjeta,
                               @RequestParam(required = false) String vencimientoTarjeta,
                               @RequestParam(required = false) String cvvTarjeta,
                               @RequestParam(required = false) String tipoTarjeta,
                               @RequestParam(required = false) String franquiciaTarjeta,
                               @RequestParam(required = false) String bancoPse,
                               @RequestParam(required = false) String tipoDocumentoPse,
                               @RequestParam(required = false) String documentoPse,
                               @RequestParam(required = false) String telefonoNequi,
                               @RequestParam(required = false) String codigoNequi,
                               RedirectAttributes redirectAttributes) {

        if (userDetails == null) {
            return "redirect:/login";
        }

        if (cartService.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "El carrito está vacío.");
            return "redirect:/carrito";
        }

        Usuario usuario = usuarioService.obtenerPorEmail(userDetails.getUsername());
        if (usuario == null) {
            redirectAttributes.addFlashAttribute("error", "Sesión inválida.");
            return "redirect:/login";
        }

        boolean datosPagoValidos = switch (metodoPago == null ? "" : metodoPago) {
            case "tarjeta" -> titularTarjeta != null && !titularTarjeta.isBlank()
                    && datosTarjetaValidos(franquiciaTarjeta, numeroTarjeta, cvvTarjeta)
                    && vencimientoValido(vencimientoTarjeta)
                    && tipoTarjeta != null && Set.of("credito", "debito").contains(tipoTarjeta)
                    && franquiciaTarjeta != null && Set.of("visa", "mastercard", "amex").contains(franquiciaTarjeta);
            case "pse" -> bancoPse != null && BANCOS_PSE.contains(bancoPse)
                    && tipoDocumentoPse != null && Set.of("CC", "CE").contains(tipoDocumentoPse)
                    && documentoPse != null && documentoPse.matches("\\d{5,12}");
            case "nequi" -> telefonoNequi != null && telefonoNequi.matches("3\\d{9}")
                    && codigoNequi != null && codigoNequi.matches("\\d{4,6}");
            default -> false;
        };

        if (!datosPagoValidos) {
            redirectAttributes.addFlashAttribute("error", mensajeDatosPago(metodoPago));
            return "redirect:/checkout";
        }

        // Recalculo en servidor: nunca se confía en un total enviado desde el formulario
        double subtotal = cartService.getTotal();
        double iva = subtotal * 0.19;
        double envio = Pedido.COSTO_ENVIO_ACTUAL;
        double total = subtotal + iva + envio;

        try {
            Pedido pedido = pedidoService.crearPedido(
                    cartService.getItems(), usuario.getNombre(), usuario.getEmail(),
                    usuario.getTelefono(), usuario.getDireccion(), usuario, total, iva, envio);

            pedidoService.procesarPagoPedido(pedido.getId(), true);
            cartService.clear();

            redirectAttributes.addFlashAttribute("success",
                    "¡Pago  aprobado " + nombreMetodoPago(metodoPago) + "! Pedido #" + pedido.getId()
                            + " registrado correctamente. Se ha enviado un correo de confirmación.");

            try {
                emailService.enviarCorreoConfirmacionCompra(
                        usuario.getEmail(), usuario.getNombre(), pedido.getId(), total);
            } catch (MailException e) {
                logger.error("No se pudo enviar el correo de confirmación del pedido {}", pedido.getId(), e);
                redirectAttributes.addFlashAttribute("error",
                        "El pedido quedó registrado, pero no se pudo enviar el correo de confirmación.");
            }
            return "redirect:/perfil";
        } catch (IllegalStateException e) {
            logger.warn("No se pudo registrar el pedido: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/checkout";
        }
    }

    private boolean datosTarjetaValidos(String franquicia, String numero, String cvv) {
        if (franquicia == null || numero == null || cvv == null) {
            return false;
        }

        String numeroNormalizado = numero.replaceAll("\\s", "");
        if (!numeroNormalizado.matches("\\d{13,19}")) {
            return false;
        }

        return switch (franquicia) {
            case "visa" -> Set.of(13, 16, 19).contains(numeroNormalizado.length())
                    && cvv.matches("\\d{3}");
            case "mastercard" -> numeroNormalizado.length() == 16 && cvv.matches("\\d{3}");
            case "amex" -> numeroNormalizado.length() == 15 && cvv.matches("\\d{4}");
            default -> false;
        };
    }

    private boolean vencimientoValido(String vencimiento) {
        if (vencimiento == null || !vencimiento.matches("(0[1-9]|1[0-2])/\\d{2}")) {
            return false;
        }

        int mes = Integer.parseInt(vencimiento.substring(0, 2));
        int anio = 2000 + Integer.parseInt(vencimiento.substring(3, 5));
        return !YearMonth.of(anio, mes).isBefore(YearMonth.now());
    }

    private String mensajeDatosPago(String metodoPago) {
        return switch (metodoPago == null ? "" : metodoPago) {
            case "tarjeta" -> "Revisa el nombre, número, vencimiento y CVV. Deben corresponder al tipo y franquicia seleccionados.";
            case "pse" -> "Selecciona un banco y tipo de documento, e ingresa un número de documento válido.";
            case "nequi" -> "Ingresa un número celular colombiano de 10 dígitos que empiece por 3 y un código de 4 a 6 dígitos.";
            default -> "Selecciona un método de pago válido.";
        };
    }

    private String nombreMetodoPago(String metodoPago) {
        return switch (metodoPago) {
            case "tarjeta" -> "tarjeta";
            case "pse" -> "PSE";
            case "nequi" -> "Nequi";
            default -> throw new IllegalArgumentException("Método de pago no soportado.");
        };
    }
}