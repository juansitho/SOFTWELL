package com.softwell.softwell.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.softwell.softwell.model.Usuario;
import com.softwell.softwell.service.EmailService;
import com.softwell.softwell.service.UsuarioService;

@Controller
public class RegistroController {

    private static final Logger logger = LoggerFactory.getLogger(RegistroController.class);

    private final UsuarioService usuarioService;
    private final EmailService emailService;

    public RegistroController(UsuarioService usuarioService, EmailService emailService) {
        this.usuarioService = usuarioService;
        this.emailService = emailService;
    }

    @GetMapping("/registro")
    public String registroPage() {
        return "registro";
    }

    @PostMapping("/registro")
    public String procesarRegistro(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String telefono,
            @RequestParam(required = false) String direccion,
            @RequestParam(required = false) Double latitud,
            @RequestParam(required = false) Double longitud,
            @RequestParam(required = false) String rol,
            RedirectAttributes redirectAttributes,
            Model model) {

        // 1. Validación de campos obligatorios
        if (estaVacio(nombre) || estaVacio(email) || estaVacio(password)) {
            return volverAlFormulario(model,
                    "Nombre, email y contraseña son campos obligatorios.",
                    nombre, email, telefono, direccion);
        }

        // 2. Registro del usuario
        Usuario usuario;
        try {
            usuario = usuarioService.registrarUsuario(
                    email, password, nombre, telefono, direccion, latitud, longitud, rol);
        } catch (RuntimeException e) {
            return volverAlFormulario(model, e.getMessage(),
                    nombre, email, telefono, direccion);
        }

        redirectAttributes.addFlashAttribute(
                "success", "¡Te has registrado exitosamente! Ahora puedes iniciar sesión.");

        // 3. Envío del correo de bienvenida (un fallo aquí no debe cancelar el registro)
        try {
            emailService.enviarCorreoRegistro(usuario.getEmail(), usuario.getNombre());
        } catch (MailException e) {
            logger.error("No se pudo enviar el correo de bienvenida a {}", usuario.getEmail(), e);
            redirectAttributes.addFlashAttribute(
                    "error", "La cuenta se creó correctamente, pero no se pudo enviar el correo de bienvenida.");
        }

        return "redirect:/login";
    }

    // ---------- Métodos auxiliares ----------

    private boolean estaVacio(String valor) {
        return valor == null || valor.trim().isEmpty();
    }

    private String volverAlFormulario(Model model, String error,
                                      String nombre, String email,
                                      String telefono, String direccion) {
        model.addAttribute("error", error);
        model.addAttribute("nombre", nombre);
        model.addAttribute("email", email);
        model.addAttribute("telefono", telefono);
        model.addAttribute("direccion", direccion);
        return "registro";
    }
}