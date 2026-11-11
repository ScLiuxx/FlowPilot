package com.flowpilot.auth.controller;

import com.flowpilot.auth.dto.AuthDtos;
import com.flowpilot.auth.service.AuthService;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<AuthDtos.UserProfile> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    public Result<AuthDtos.UserProfile> me() {
        return Result.success(authService.profile(CurrentUser.requireId()));
    }
}
