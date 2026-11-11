package com.nereonix.nereonet_mvp.core.passwordreset;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Signed password reset tokens (P14), as InvitationTokenService signs invitations (HS256, the same secret):
 * jti = tokenId, sub = email, exp = expiresAt. The audience "password-reset" is required when parsing, so an
 * invitation token is never accepted as a reset token (it has no audience).
 */
@Service
public class PasswordResetTokenService {

    static final String AUDIENCE = "password-reset";

    private final SecretKey key;

    public PasswordResetTokenService(@Value("${app.invitation.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String create(PasswordReset reset) {
        return Jwts.builder()
                .id(reset.getTokenId().toString())
                .subject(reset.getEmail())
                .audience().add(AUDIENCE).and()
                .expiration(Date.from(reset.getExpiresAt()))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // Returns the tokenId. Throws JwtException if the signature or audience is wrong or the token expired.
    public UUID parse(String token) {
        String id = Jwts.parser()
                .verifyWith(key)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getId();
        if (id == null) {
            throw new JwtException("Token has no id");
        }
        return UUID.fromString(id);
    }
}
