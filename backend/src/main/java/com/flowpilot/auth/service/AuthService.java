package com.flowpilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowpilot.auth.dto.AuthDtos;
import com.flowpilot.auth.security.JwtTokenProvider;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.util.Ids;
import com.flowpilot.user.entity.SysUser;
import com.flowpilot.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Registration, login and profile lookup.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional
    public AuthDtos.UserProfile register(AuthDtos.RegisterRequest request) {
        LambdaQueryWrapper<SysUser> existing = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername());
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            existing.or().eq(SysUser::getEmail, request.getEmail());
        }
        if (userMapper.selectCount(existing) > 0) {
            throw new BizException(ErrorCode.USER_ALREADY_EXISTS);
        }

        SysUser user = SysUser.builder()
                .id(idGenerator.nextId())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname() == null || request.getNickname().isBlank()
                        ? request.getUsername() : request.getNickname())
                .email(request.getEmail())
                .role("USER")
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        return toProfile(user);
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        String token = tokenProvider.issue(user.getId(), user.getUsername(), user.getRole());
        return AuthDtos.LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getExpirationSeconds())
                .user(toProfile(user))
                .build();
    }

    public AuthDtos.UserProfile profile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toProfile(user);
    }

    private AuthDtos.UserProfile toProfile(SysUser user) {
        return AuthDtos.UserProfile.builder()
                .id(Ids.of(user.getId()))
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
