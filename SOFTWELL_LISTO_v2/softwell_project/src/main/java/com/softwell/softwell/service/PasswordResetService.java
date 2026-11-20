package com.softwell.softwell.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.softwell.softwell.model.PasswordResetCode;
import com.softwell.softwell.model.Usuario;
import com.softwell.softwell.repository.PasswordResetCodeRepository;
import com.softwell.softwell.repository.UsuarioRepository;

@Service
public class PasswordResetService {

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordResetCodeRepository resetCodeRepository;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioService usuarioService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetService(
            PasswordResetCodeRepository resetCodeRepository,
            UsuarioRepository usuarioRepository,
            UsuarioService usuarioService,
            EmailService emailService,
            PasswordEncoder passwordEncoder) {
        this.resetCodeRepository = resetCodeRepository;
        this.usuarioRepository = usuarioRepository;
        this.usuarioService = usuarioService;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    public void solicitarCodigo(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email.trim());
        if (usuarioOpt.isEmpty()) {
            return;
        }

        Usuario usuario = usuarioOpt.get();
        Instant ahora = Instant.now();
        Optional<PasswordResetCode> existente = resetCodeRepository.findById(usuario.getEmail());
        if (existente.isPresent()
                && ahora.isBefore(existente.get().getSentAt().plus(RESEND_COOLDOWN))) {
            return;
        }

        String codigo = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        PasswordResetCode resetCode = new PasswordResetCode();
        resetCode.setEmail(usuario.getEmail());
        resetCode.setCodeHash(passwordEncoder.encode(codigo));
        resetCode.setExpiresAt(ahora.plus(CODE_LIFETIME));
        resetCode.setSentAt(ahora);
        resetCode.setAttempts(0);
        resetCodeRepository.save(resetCode);

        emailService.enviarCodigoRestablecimiento(usuario.getEmail(), codigo);
    }

    public void restablecer(String email, String codigo, String nuevaPassword) {
        String correo = email == null ? "" : email.trim();
        PasswordResetCode resetCode = resetCodeRepository.findById(correo)
                .orElseThrow(() -> new IllegalArgumentException("El código no es válido o ha vencido."));

        if (!Instant.now().isBefore(resetCode.getExpiresAt())
                || resetCode.getAttempts() >= MAX_ATTEMPTS) {
            resetCodeRepository.delete(resetCode);
            throw new IllegalArgumentException("El código no es válido o ha vencido.");
        }

        if (codigo == null || !passwordEncoder.matches(codigo.trim(), resetCode.getCodeHash())) {
            resetCode.setAttempts(resetCode.getAttempts() + 1);
            if (resetCode.getAttempts() >= MAX_ATTEMPTS) {
                resetCodeRepository.delete(resetCode);
            } else {
                resetCodeRepository.save(resetCode);
            }
            throw new IllegalArgumentException("El código no es válido o ha vencido.");
        }

        usuarioService.actualizarPasswordPorEmail(correo, nuevaPassword);
        resetCodeRepository.delete(resetCode);
    }
}
