package com.bookvillage.mock.dto;

import com.bookvillage.mock.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String name;
    private String phone;
    private String address;
    private String role;
    private String status;
    private LocalDateTime suspendedUntil;
    private LocalDateTime createdAt;

    public static UserDto from(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setName(user.getName());
        dto.setPhone(user.getPhone());
        dto.setAddress(user.getAddress());
        dto.setRole(user.getRole());
        dto.setStatus(user.getStatus());
        dto.setSuspendedUntil(user.getSuspendedUntil());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}
