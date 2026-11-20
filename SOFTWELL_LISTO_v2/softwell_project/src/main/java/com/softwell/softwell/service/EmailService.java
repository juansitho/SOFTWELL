package com.softwell.softwell.service;

import java.text.NumberFormat;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String senderAddress;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String senderAddress) {
        this.mailSender = mailSender;
        this.senderAddress = senderAddress;
    }

    public void sendNotification(String recipient, String subject, String message) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(senderAddress);
        email.setTo(recipient);
        email.setSubject(subject);
        email.setText(message);
        mailSender.send(email);
    }

    public void enviarCorreoRegistro(String correoCliente, String nombreCliente) {
        String mensaje = String.format("""
            Hola, %s:

            ¡Te damos la bienvenida a SOFTWELL! Tu cuenta en nuestra joyería se ha creado exitosamente.

            Gracias por formar parte de nuestra comunidad. Esperamos acompañarte en tus próximos momentos especiales.

            Saludos,
            El equipo de SOFTWELL
            """, nombreCliente);
            
        sendNotification(correoCliente, "¡Bienvenido a SOFTWELL!", mensaje);
    }

    public void enviarCodigoRestablecimiento(String correoCliente, String codigo) {
        String mensaje = String.format("""
            Recibimos una solicitud para restablecer la contraseña de tu cuenta SOFTWELL.

            Tu código de verificación es: %s

            El código vence en 10 minutos. Si no solicitaste este cambio, ignora este correo.
            """, codigo);

        sendNotification(correoCliente, "Código para restablecer tu contraseña - SOFTWELL", mensaje);
    }

    public void enviarCorreoConfirmacionCompra(
            String correoCliente, String nombreCliente, String idCompra, double total) {
        NumberFormat formato = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
        formato.setMinimumFractionDigits(0);
        formato.setMaximumFractionDigits(0);
        String montoFormateado = "COP " + formato.format(total);
        
        String mensaje = String.format("""
            Hola, %s:

            ¡Gracias por elegir SOFTWELL! Recibimos tu pedido correctamente.

            Número de orden: %s
            Monto total: %s

            El pago fue realizado y pagado con exito, nos pondremos en contacto contigo para la entrega.

            Gracias por tu confianza.

            Saludos,
            SOFTWELL tu joyeria de confianza
            """, nombreCliente, idCompra, montoFormateado);
            
        sendNotification(correoCliente, "Pedido recibido en SOFTWELL", mensaje);
    }
}