package com.nereonix.nereonet_mvp.core.invitation;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Signed invitation tokens (HS256): jti = tokenId, sub = email, exp = expiresAt.
@Service
public class InvitationTokenService {

    private final SecretKey key;

    // The secret must be at least 32 bytes (256 bits) for HS256.
    public InvitationTokenService(@Value("${app.invitation.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String create(Invitation invitation) {
        return Jwts.builder()
                .id(invitation.getTokenId().toString())
                .subject(invitation.getEmail())
                .expiration(Date.from(invitation.getExpiresAt()))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // Returns the tokenId. Throws JwtException if the signature is wrong or the token expired.
    public UUID parse(String token) {
        String id = Jwts.parser()
                .verifyWith(key)
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
