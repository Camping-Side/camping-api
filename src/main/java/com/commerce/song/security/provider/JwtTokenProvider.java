package com.commerce.song.security.provider;

import com.camping.common.domain.dto.UserContextDto;
import com.commerce.song.domain.dto.TokenDto;
import com.commerce.song.domain.entity.Account;
import com.commerce.song.domain.entity.Role;
import com.commerce.song.exception.JwtNotAvailbleException;
import com.commerce.song.repository.AccountRepository;
import com.commerce.song.security.token.CustomContextToken;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.Key;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider implements InitializingBean {
    @Autowired
    private AccountRepository accountRepository;
    private final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String AUTHORITIES_KEY = "auth";
    private static final String BEARER_TYPE = "bearer";

    private final String secret;
    private final long accessTokenExpireTime;
    private final long refreshTokenExpireTime;
    private Key key;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.access-token-expire-time}")long accessTokenExpireTime,
                            @Value("${jwt.refresh-token-expire-time}")long refreshTokenExpireTime
                            ) {
        this.secret = secret;
        this.accessTokenExpireTime = accessTokenExpireTime * 1000;
        this.refreshTokenExpireTime = refreshTokenExpireTime * 1000;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    // 토큰 생성
    public TokenDto createToken(Authentication authentication) {
        // 권한들 가져오기
        String authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        long now = (new Date()).getTime();
        Date accessTokenExpiresIn = new Date(now + this.accessTokenExpireTime);

        String accessToken = Jwts.builder()
                .setSubject(authentication.getName())
                .claim(AUTHORITIES_KEY, authorities)
                .setExpiration(accessTokenExpiresIn)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();

        Date refreshTokenExpiresIn = new Date(now + this.refreshTokenExpireTime);
        String refreshToken = Jwts.builder()
                .setExpiration(refreshTokenExpiresIn)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();

        return TokenDto.builder()
                .grantType(BEARER_TYPE)
                .accessToken(accessToken)
                .accessTokenExpiresIn(accessTokenExpiresIn.getTime())
                .refreshToken(refreshToken)
                .email(authentication.getName())
                .build();

    }

    // 토큰을 받아 Claim을 만들음.
    // claim에서 권한을 받아 유저객체를 만듬 최종적으론 authentication 객체 리턴
    @Transactional(readOnly = true)
    public Authentication getAuthentication(String accessToken) {
        Claims claims = parseClaims(accessToken);

        if(claims.get(AUTHORITIES_KEY) == null) {
            throw new RuntimeException("권한 정보가 없는 토큰입니다.");
        }

        Account account = accountRepository.findByEmail(claims.getSubject());

        if(!account.isActivated()) {
            throw new JwtNotAvailbleException("expired account");
        }

        Set<Role> userRoles = account.getUserRoles();
        Set<String> roleSet = userRoles.stream().map(Role::getRoleNm).collect(Collectors.toSet());

        UserContextDto user = UserContextDto.builder()
                .id(account.getId())
                .username(account.getEmail())
                .password(account.getPassword())
                .roles(roleSet).build();

        return CustomContextToken.getTokenFromAccountContext(user);
    }

    // 토큰 유효성 검사
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch(io.jsonwebtoken.security.SecurityException  | MalformedJwtException e) {
            logger.info("잘못된 JWT 서명입니다.");
            throw new JwtNotAvailbleException("잘못된 JWT 서명입니다.");
        } catch (ExpiredJwtException e) {
            logger.info("만료된 JWT 토큰입니다.");
            throw new JwtNotAvailbleException("만료된 JWT 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            logger.info("지원되지 않는 JWT 토큰입니다.");
            throw new JwtNotAvailbleException("지원되지 않는 JWT 토큰입니다.");
        } catch (IllegalArgumentException e) {
            logger.info("JWT 토큰이 잘못되었습니다.");
            throw new JwtNotAvailbleException("JWT 토큰이 잘못되었습니다.");
        }
    }

    private Claims parseClaims(String accessToken) {
        try {
            return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(accessToken).getBody();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }
}
