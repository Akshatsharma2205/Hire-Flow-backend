package com.kartgen.hireflow.security;

import com.kartgen.hireflow.config.HireFlowProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final HireFlowProperties props;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UUID userId, String email, UUID organizationId, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(userId.toString())
                .claims(Map.of(
                        "email", email,
                        "orgId", organizationId.toString(),
                        "role", role != null ? role : "RECRUITER"
                ))
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getJwt().getExpirationMs()))
                .signWith(getKey())
                .compact();
    }

    public Claims validateAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .requireIssuer(props.getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
