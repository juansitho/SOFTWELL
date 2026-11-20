package com.softwell.softwell.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.softwell.softwell.service.PasswordResetService;

@Controller
public class PasswordResetController {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);
    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/recuperar-contrasena")
    public String mostrarSolicitud() {
        return "recuperar-contrasena";
    }

    @PostMapping("/recuperar-contrasena")
    public String solicitarCodigo(
            @RequestParam(required = false) String email,
            RedirectAttributes redirectAttributes) {
        try {
            passwordResetService.solicitarCodigo(email);
            redirectAttributes.addFlashAttribute(
                    "success", "Si el correo corresponde a una cuenta, recibirás un código en breve.");
        } catch (MailException e) {
            logger.error("No se pudo enviar el correo de restablecimiento.", e);
            redirectAttributes.addFlashAttribute(
                    "error", "No se pudo enviar el correo ahora. Inténtalo más tarde.");
        }
        return "redirect:/recuperar-contrasena";
    }

    @GetMapping("/restablecer-contrasena")
    public String mostrarRestablecimiento() {
        return "restablecer-contrasena";
    }

    @PostMapping("/restablecer-contrasena")
    public String restablecer(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String codigo,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String confirmarPassword,
            RedirectAttributes redirectAttributes) {
        if (password == null || password.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "La contraseña debe tener al menos 8 caracteres.");
            return "redirect:/restablecer-contrasena";
        }
        if (!password.equals(confirmarPassword)) {
            redirectAttributes.addFlashAttribute("error", "Las contraseñas no coinciden.");
            return "redirect:/restablecer-contrasena";
        }

        try {
            passwordResetService.restablecer(email, codigo, password);
            redirectAttributes.addFlashAttribute(
                    "success", "Tu contraseña se actualizó. Ya puedes iniciar sesión.");
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/restablecer-contrasena";
        }
    }
}
