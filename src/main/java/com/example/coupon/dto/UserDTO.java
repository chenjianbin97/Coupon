package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDTO {

    private Long id;

    private String username;

    private String phone;

    private String email;

    private String nickname;

    private String avatar;

    private Integer status;

    private Long shopNumber;
}
