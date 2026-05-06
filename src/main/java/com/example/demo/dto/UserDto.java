package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Standard user payload")
public class UserDto {
    @Schema(example = "USER")
    private String role;

    @Schema(example = "user")
    private String username;

    @Schema(example = "Standard User")
    private String displayName;

    @Schema(description = "Items the user can see")
    private List<String> visibleItems;

    @Schema(example = "Welcome, user!")
    private String message;
}
